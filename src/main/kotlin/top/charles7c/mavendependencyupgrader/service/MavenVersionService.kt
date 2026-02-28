package top.charles7c.mavendependencyupgrader.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.io.HttpRequests
import top.charles7c.mavendependencyupgrader.util.VersionUtils
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Maven 版本查询服务，通过 Maven Central Search API 获取最新版本信息
 */
@Service(Service.Level.APP)
class MavenVersionService {

    private val logger = thisLogger()

    // Maven Central Search API 端点
    private val SEARCH_BASE_URL = "https://search.maven.org/solrsearch/select"

    /**
     * 获取指定 Maven 坐标的最新稳定版本
     *
     * @return 最新稳定版本字符串，若查询失败则返回 null
     */
    fun getLatestStableVersion(groupId: String, artifactId: String): String? {
        return try {
            val url = buildSearchUrl(groupId, artifactId, rows = 1)
            val response = HttpRequests.request(url)
                .connectTimeout(8_000)
                .readTimeout(15_000)
                .readString()
            parseLatestVersion(response)
        } catch (e: IOException) {
            logger.debug("Failed to fetch latest version for $groupId:$artifactId", e)
            null
        } catch (e: Exception) {
            logger.warn("Unexpected error fetching version for $groupId:$artifactId: ${e.message}")
            null
        }
    }

    /**
     * 获取指定 Maven 坐标的所有可用稳定版本，按版本号降序排列
     *
     * @return 版本列表（稳定版，降序），若查询失败则返回空列表
     */
    fun getAvailableVersions(groupId: String, artifactId: String): List<String> {
        return try {
            val url = buildSearchUrl(groupId, artifactId, core = "gav", rows = 100)
            val response = HttpRequests.request(url)
                .connectTimeout(8_000)
                .readTimeout(15_000)
                .readString()
            val allVersions = parseAllVersions(response)
            VersionUtils.filterAndSortStableVersions(allVersions)
        } catch (e: IOException) {
            logger.debug("Failed to fetch versions for $groupId:$artifactId", e)
            emptyList()
        } catch (e: Exception) {
            logger.warn("Unexpected error fetching versions for $groupId:$artifactId: ${e.message}")
            emptyList()
        }
    }

    private fun buildSearchUrl(
        groupId: String,
        artifactId: String,
        core: String? = null,
        rows: Int = 20
    ): String {
        val g = URLEncoder.encode(groupId, StandardCharsets.UTF_8)
        val a = URLEncoder.encode(artifactId, StandardCharsets.UTF_8)
        val query = URLEncoder.encode("g:$groupId AND a:$artifactId", StandardCharsets.UTF_8)
        return buildString {
            append("$SEARCH_BASE_URL?q=$query")
            if (core != null) append("&core=$core")
            append("&rows=$rows&wt=json")
        }
    }

    /**
     * 从 Maven Central 响应中提取 latestVersion 字段
     * 响应格式：{"response":{"docs":[{"latestVersion":"x.y.z",...}]}}
     */
    private fun parseLatestVersion(json: String): String? {
        return Regex(""""latestVersion"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.getOrNull(1)
    }

    /**
     * 从 core=gav 响应中提取所有版本号列表
     * 响应格式：{"response":{"docs":[{"v":"x.y.z",...}]}}
     */
    private fun parseAllVersions(json: String): List<String> {
        return Regex(""""v"\s*:\s*"([^"]+)"""").findAll(json)
            .map { it.groupValues[1] }
            .toList()
    }
}
