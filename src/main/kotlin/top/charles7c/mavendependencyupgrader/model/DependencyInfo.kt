package top.charles7c.mavendependencyupgrader.model

/**
 * Maven 依赖信息
 */
class DependencyInfo(
    val groupId: String,
    val artifactId: String,
    /** 当前使用的版本（已解析属性） */
    val currentVersion: String,
    /** pom.xml 中原始版本表达式，可能为属性引用如 ${spring.version} */
    val versionExpression: String = currentVersion,
    val scope: String = "compile",
    /** 所属 pom.xml 文件路径 */
    val pomFilePath: String
) {
    /** 最新稳定版本 */
    @Volatile var latestVersion: String? = null

    /** 版本升级类型 */
    @Volatile var updateType: UpdateType = UpdateType.UNKNOWN

    /** 已知漏洞列表 */
    @Volatile var vulnerabilities: List<VulnerabilityInfo> = emptyList()

    /** 是否正在加载版本信息 */
    @Volatile var isVersionLoading: Boolean = false

    /** 是否正在扫描漏洞 */
    @Volatile var isVulnLoading: Boolean = false

    /** 是否已完成版本扫描 */
    @Volatile var versionFetched: Boolean = false

    /** 是否已完成漏洞扫描 */
    @Volatile var vulnFetched: Boolean = false

    /** 唯一标识符（groupId:artifactId） */
    val key: String get() = "$groupId:$artifactId"

    /** 是否有可用更新 */
    val hasUpdate: Boolean
        get() = updateType == UpdateType.PATCH || updateType == UpdateType.MINOR || updateType == UpdateType.MAJOR

    /** 是否存在已知漏洞 */
    val hasVulnerability: Boolean get() = vulnerabilities.isNotEmpty()

    /** 最高漏洞严重级别 */
    val maxSeverity: VulnerabilityInfo.Severity?
        get() = vulnerabilities.maxByOrNull { it.severity.priority }?.severity

    /** 是否为属性引用版本（如 ${spring.version}） */
    val isPropertyVersion: Boolean
        get() = versionExpression.startsWith("\${") && versionExpression.endsWith("}")

    /** 提取属性名称（仅当 isPropertyVersion 为 true 时有效） */
    val propertyName: String?
        get() = if (isPropertyVersion) versionExpression.substring(2, versionExpression.length - 1) else null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DependencyInfo) return false
        return groupId == other.groupId && artifactId == other.artifactId && pomFilePath == other.pomFilePath
    }

    override fun hashCode(): Int {
        var result = groupId.hashCode()
        result = 31 * result + artifactId.hashCode()
        result = 31 * result + pomFilePath.hashCode()
        return result
    }

    override fun toString(): String = "$groupId:$artifactId:$currentVersion"
}

/**
 * 版本升级类型
 */
enum class UpdateType(val label: String) {
    UP_TO_DATE("最新"),
    PATCH("补丁"),
    MINOR("次要"),
    MAJOR("主要"),
    UNKNOWN("-")
}
