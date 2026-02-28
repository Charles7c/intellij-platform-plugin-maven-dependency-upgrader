# IntelliJ Plugin: Maven Dependency Upgrader - Memory

## Project Overview
- **Plugin ID**: `top.charles7c.mavendependencyupgrader`
- **Target Platform**: IntelliJ IDEA 2025.1.7 (build 251+)
- **Language**: Kotlin 2.3.0 + JVM 21
- **Build**: Gradle 9.3.1 with IntelliJ Platform Gradle Plugin 2.11.0

## Package Structure
```
top.charles7c.mavendependencyupgrader/
├── model/
│   ├── DependencyInfo.kt        # Maven 依赖信息（regular class with @Volatile vars）
│   └── VulnerabilityInfo.kt     # 漏洞信息（data class）
├── util/
│   └── VersionUtils.kt          # 版本比较工具（semver: PATCH/MINOR/MAJOR）
├── service/
│   ├── MavenDependencyService.kt # @Service(PROJECT) - 解析 pom.xml，执行升级
│   ├── MavenVersionService.kt    # @Service(APP) - Maven Central API 查询最新版本
│   └── VulnerabilityService.kt   # @Service(APP) - OSS Index API 漏洞扫描
├── ui/
│   ├── DependencyTableModel.kt   # AbstractTableModel + 内置过滤（search, hasUpdate, hasVuln）
│   ├── DependencyDetailPanel.kt  # 详情面板（版本 + 漏洞信息）
│   └── MavenDependencyPanel.kt   # 主面板（toolbar + filter bar + JBTable + detail）
└── toolWindow/
    └── MavenDependencyToolWindowFactory.kt
```

## Key Design Decisions
- No external JSON libs: Maven Central parsed with regex; OSS Index with bracket-stack parser
- HTTP calls: `com.intellij.util.io.HttpRequests` (handles proxy, timeouts)
- Threading: `AppExecutorUtil.createBoundedApplicationPoolExecutor` for parallel version fetch (10 threads)
- UI updates: always `invokeLaterIfActive { }` (checks `project.isDisposed`)
- PSI access: `ReadAction.compute<T?, Exception>` / `ReadAction.run<Exception>`
- pom.xml writes: `WriteCommandAction.runWriteCommandAction(project, name, null, Runnable { ... }, psiFile)`

## External APIs
- **Maven Central**: `https://search.maven.org/solrsearch/select?q=g:X+AND+a:Y&rows=1&wt=json`
  - Response field: `response.docs[0].latestVersion`
- **OSS Index**: `POST https://ossindex.sonatype.org/api/v3/component-report`
  - Coordinates format: `pkg:maven/groupId/artifactId@version`
  - Free, no auth, rate limit: 128 req/hour

## Bundled Plugin Dependencies (gradle.properties)
```
platformBundledPlugins = com.intellij.java, org.jetbrains.idea.maven
```
plugin.xml must declare: `<depends>com.intellij.modules.java</depends>` + `<depends>org.jetbrains.idea.maven</depends>`

## Known Patterns
- `MavenProjectsManager.getInstance(project)` → check `.isMavenizedProject` before use
- `mavenProject.file` → `VirtualFile` for pom.xml
- `mavenProject.properties` → `java.util.Properties` for property resolution
- Version property refs: `${spring.version}` → stored in `versionExpression`, resolved in `currentVersion`
