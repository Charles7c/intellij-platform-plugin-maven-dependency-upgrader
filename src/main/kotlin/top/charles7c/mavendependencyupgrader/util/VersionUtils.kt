package top.charles7c.mavendependencyupgrader.util

import top.charles7c.mavendependencyupgrader.model.UpdateType

/**
 * 版本号工具类，提供版本比较和升级类型判断功能
 */
object VersionUtils {

    private val SNAPSHOT_SUFFIXES = setOf("-SNAPSHOT", ".SNAPSHOT")
    private val PRE_RELEASE_PATTERNS = listOf("alpha", "beta", "rc", "cr", "m", "preview", "ea")

    /**
     * 判断版本是否为稳定版（非 SNAPSHOT 且非预发布版）
     */
    fun isStable(version: String): Boolean {
        val lower = version.lowercase()
        if (SNAPSHOT_SUFFIXES.any { version.uppercase().endsWith(it.uppercase()) }) return false
        if (PRE_RELEASE_PATTERNS.any { Regex("(?:^|[.-])$it[.-]?\\d*(?:$|[.-])", RegexOption.IGNORE_CASE).containsMatchIn(lower) }) return false
        return true
    }

    /**
     * 比较两个版本号，返回负数（v1 < v2）、0（相等）或正数（v1 > v2）
     */
    fun compare(v1: String, v2: String): Int {
        val parts1 = parseVersionParts(v1)
        val parts2 = parseVersionParts(v2)
        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1.compareTo(p2)
        }
        return 0
    }

    /**
     * 根据当前版本和最新版本，判断升级类型
     */
    fun determineUpdateType(current: String, latest: String): UpdateType {
        if (current == latest) return UpdateType.UP_TO_DATE
        if (compare(current, latest) >= 0) return UpdateType.UP_TO_DATE

        val currentParts = parseVersionParts(current)
        val latestParts = parseVersionParts(latest)

        val currentMajor = currentParts.getOrElse(0) { 0 }
        val latestMajor = latestParts.getOrElse(0) { 0 }
        val currentMinor = currentParts.getOrElse(1) { 0 }
        val latestMinor = latestParts.getOrElse(1) { 0 }

        return when {
            latestMajor > currentMajor -> UpdateType.MAJOR
            latestMinor > currentMinor -> UpdateType.MINOR
            else -> UpdateType.PATCH
        }
    }

    /**
     * 从版本列表中筛选稳定版本，并按版本号降序排列
     */
    fun filterAndSortStableVersions(versions: List<String>): List<String> {
        return versions.filter { isStable(it) }.sortedWith { a, b -> compare(b, a) }
    }

    /**
     * 解析版本号字符串为数字列表（取第一个非数字前缀的数字段）
     */
    private fun parseVersionParts(version: String): List<Int> {
        // 去掉 SNAPSHOT 等后缀，取主版本号部分
        val clean = version.replace(Regex("(?i)[-.]?(snapshot|alpha|beta|rc|cr|m|preview|ea)[.\\d-]*$"), "")
        return clean.split("[.\\-_]".toRegex())
            .mapNotNull { it.toIntOrNull() }
            .takeWhile { true }
    }
}
