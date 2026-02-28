package top.charles7c.mavendependencyupgrader.service

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import top.charles7c.mavendependencyupgrader.model.DependencyInfo

/**
 * Maven 依赖管理服务，负责解析 pom.xml 和执行版本升级
 */
@Service(Service.Level.PROJECT)
class MavenDependencyService(private val project: Project) {

    private val logger = thisLogger()

    /**
     * 读取项目中所有 pom.xml 的直接依赖
     *
     * 版本解析优先级：
     *   1. pom.xml 中显式的 <version> 标签
     *   2. <version> 是属性引用（如 ${spring.version}）→ 从项目属性解析
     *   3. 无 <version> 标签（由父 POM dependencyManagement 管理）→ 从 Maven 解析后的依赖列表获取
     */
    fun getDependencies(): List<DependencyInfo> {
        val manager = MavenProjectsManager.getInstance(project)
        if (!manager.isMavenizedProject) {
            logger.info("Not a Maven project: ${project.name}")
            return emptyList()
        }

        val mavenProjects = manager.projects
        if (mavenProjects.isEmpty()) {
            logger.info("Maven projects list is empty, import may still be in progress")
            return emptyList()
        }

        val result = mutableListOf<DependencyInfo>()

        for (mavenProject in mavenProjects) {
            val pomFile = mavenProject.file
            val pomPath = pomFile.path

            // 构建已解析版本的映射（用于无显式 <version> 标签的场景）
            val resolvedVersionMap = buildResolvedVersionMap(mavenProject)

            val psiFile = ReadAction.compute<XmlFile?, Exception> {
                PsiManager.getInstance(project).findFile(pomFile) as? XmlFile
            } ?: continue

            ReadAction.run<Exception> {
                val rootTag = psiFile.rootTag ?: return@run
                parseDependencies(rootTag, mavenProject, resolvedVersionMap, pomPath, result)
            }
        }

        return result
            .distinctBy { "${it.groupId}:${it.artifactId}:${it.pomFilePath}" }
            .sortedWith(compareBy({ it.groupId }, { it.artifactId }))
    }

    private fun buildResolvedVersionMap(mavenProject: MavenProject): Map<String, String> {
        return try {
            mavenProject.dependencies.associate { artifact ->
                "${artifact.groupId}:${artifact.artifactId}" to artifact.version
            }
        } catch (e: Exception) {
            logger.debug("Failed to build resolved version map", e)
            emptyMap()
        }
    }

    private fun parseDependencies(
        rootTag: XmlTag,
        mavenProject: MavenProject,
        resolvedVersionMap: Map<String, String>,
        pomPath: String,
        result: MutableList<DependencyInfo>
    ) {
        val dependenciesTag = rootTag.findFirstSubTag("dependencies") ?: return

        for (depTag in dependenciesTag.findSubTags("dependency")) {
            val groupId = depTag.findFirstSubTag("groupId")?.value?.text?.trim() ?: continue
            val artifactId = depTag.findFirstSubTag("artifactId")?.value?.text?.trim() ?: continue
            val scope = depTag.findFirstSubTag("scope")?.value?.text?.trim() ?: "compile"
            val versionText = depTag.findFirstSubTag("version")?.value?.text?.trim()

            val resolvedVersion = resolveVersion(
                groupId, artifactId, versionText, mavenProject, resolvedVersionMap
            ) ?: continue

            result.add(
                DependencyInfo(
                    groupId = groupId,
                    artifactId = artifactId,
                    currentVersion = resolvedVersion,
                    versionExpression = versionText ?: resolvedVersion,
                    scope = scope,
                    pomFilePath = pomPath
                )
            )
        }
    }

    /**
     * 解析依赖版本（含属性引用和父 POM 管理版本）
     */
    private fun resolveVersion(
        groupId: String,
        artifactId: String,
        versionText: String?,
        mavenProject: MavenProject,
        resolvedVersionMap: Map<String, String>
    ): String? {
        return when {
            // 无显式 version 标签：由父 POM dependencyManagement 管理
            versionText == null ->
                resolvedVersionMap["$groupId:$artifactId"]

            // 属性引用：${spring.version} 等
            versionText.startsWith("\${") && versionText.endsWith("}") -> {
                val propName = versionText.substring(2, versionText.length - 1)
                mavenProject.properties.getProperty(propName)
                    ?: resolvedVersionMap["$groupId:$artifactId"]
                    ?: versionText
            }

            // 显式版本号
            else -> versionText
        }
    }

    /**
     * 升级单个依赖到指定版本
     */
    fun upgradeDependency(dep: DependencyInfo, newVersion: String) {
        upgradeDependencies(listOf(dep to newVersion))
    }

    /**
     * 批量升级依赖到指定版本
     */
    fun upgradeDependencies(upgrades: List<Pair<DependencyInfo, String>>) {
        val byPom = upgrades.groupBy { it.first.pomFilePath }

        byPom.forEach { (pomPath, deps) ->
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(pomPath)
            if (virtualFile == null) {
                logger.warn("pom.xml not found: $pomPath")
                return@forEach
            }
            val psiFile = ReadAction.compute<XmlFile?, Exception> {
                PsiManager.getInstance(project).findFile(virtualFile) as? XmlFile
            } ?: return@forEach

            WriteCommandAction.runWriteCommandAction(project, "升级 Maven 依赖", null, Runnable {
                applyUpgrades(psiFile, deps)
            }, psiFile)
        }
    }

    private fun applyUpgrades(psiFile: XmlFile, upgrades: List<Pair<DependencyInfo, String>>) {
        val rootTag = psiFile.rootTag ?: return
        val upgradeMap = upgrades.associate { (dep, newVer) -> dep.key to (dep to newVer) }

        // 升级 <dependencies> 中的直接版本标签
        upgradeInSection(rootTag.findFirstSubTag("dependencies"), upgradeMap)

        // 同时处理 <dependencyManagement> 中的版本定义
        upgradeInSection(
            rootTag.findFirstSubTag("dependencyManagement")?.findFirstSubTag("dependencies"),
            upgradeMap
        )

        // 若版本是属性引用，同时更新 <properties> 中的属性值
        upgrades.forEach { (dep, newVersion) ->
            if (dep.isPropertyVersion && dep.propertyName != null) {
                rootTag.findFirstSubTag("properties")
                    ?.findFirstSubTag(dep.propertyName!!)
                    ?.value?.setText(newVersion)
            }
        }
    }

    private fun upgradeInSection(
        sectionTag: XmlTag?,
        upgradeMap: Map<String, Pair<DependencyInfo, String>>
    ) {
        if (sectionTag == null) return
        for (depTag in sectionTag.findSubTags("dependency")) {
            val gId = depTag.findFirstSubTag("groupId")?.value?.text?.trim() ?: continue
            val aId = depTag.findFirstSubTag("artifactId")?.value?.text?.trim() ?: continue
            val (dep, newVersion) = upgradeMap["$gId:$aId"] ?: continue
            if (!dep.isPropertyVersion) {
                depTag.findFirstSubTag("version")?.value?.setText(newVersion)
            }
        }
    }
}
