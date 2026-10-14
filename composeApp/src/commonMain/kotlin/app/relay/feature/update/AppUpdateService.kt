// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.update

import app.relay.platform.AppPlatform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 软件版本与升级信息。
 */
data class AppVersionInfo(
    val currentVersion: String,
    val latestVersion: String,
    val hasUpdate: Boolean,
    val releaseNotes: String? = null,
    val downloadUrl: String? = null,
)

/**
 * 检查更新的状态机状态。
 */
enum class UpdateCheckStatus {
    Idle,
    Checking,
    UpToDate,
    UpdateAvailable,
    Error,
}

data class UpdateCheckState(
    val status: UpdateCheckStatus = UpdateCheckStatus.Idle,
    val info: AppVersionInfo? = null,
    val errorMessage: String? = null,
)

/**
 * 剥离版本名前缀（如 "relay-0.1.0" -> "0.1.0", "v0.61.1" -> "0.61.1"）。
 */
internal fun cleanVersionString(v: String): String =
    v.trim().replace(Regex("^[a-zA-Z\\-_]+"), "")

/**
 * 语义化版本号对比。
 * current < target 返回负数；current == target 返回 0；current > target 返回正数。
 */
internal fun compareSemanticVersions(current: String, target: String): Int {
    val cParts = cleanVersionString(current).split('.').mapNotNull { seg ->
        seg.takeWhile { it.isDigit() }.toIntOrNull()
    }
    val tParts = cleanVersionString(target).split('.').mapNotNull { seg ->
        seg.takeWhile { it.isDigit() }.toIntOrNull()
    }
    val maxLen = maxOf(cParts.size, tParts.size)
    for (i in 0 until maxLen) {
        val c = cParts.getOrElse(i) { 0 }
        val t = tParts.getOrElse(i) { 0 }
        if (c != t) return c.compareTo(t)
    }
    return 0
}

interface AppUpdateChecker {
    suspend fun check(currentVersion: String): AppVersionInfo
}

/**
 * 跨平台应用更新检查器默认实现。
 *
 * 支持传入指定的更新元数据 JSON 地址（例如 GitHub Releases API 或静态 CDN version.json）；
 * 若无自定义源或未联网，则基于当前版本与本地/局域网最新版本对比。
 */
class DefaultAppUpdateChecker(
    private val platform: AppPlatform,
    private val updateCheckUrl: String? = null,
    private val fallbackLatestVersion: String? = null,
    private val fallbackDownloadUrl: String? = null,
) : AppUpdateChecker {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun check(currentVersion: String): AppVersionInfo {
        if (!updateCheckUrl.isNullOrBlank()) {
            try {
                val response = platform.httpClient.get(updateCheckUrl)
                if (response.statusCode in 200..299) {
                    val root = json.parseToJsonElement(response.body).jsonObject
                    val remoteVersion = root["version"]?.jsonPrimitive?.content ?: currentVersion
                    val notes = root["notes"]?.jsonPrimitive?.content
                    val url = root["url"]?.jsonPrimitive?.content ?: fallbackDownloadUrl
                    val hasUpdate = compareSemanticVersions(currentVersion, remoteVersion) < 0
                    return AppVersionInfo(
                        currentVersion = currentVersion,
                        latestVersion = remoteVersion,
                        hasUpdate = hasUpdate,
                        releaseNotes = notes,
                        downloadUrl = url,
                    )
                }
            } catch (t: Throwable) {
                // HTTP check failed, fallback to local/fallback evaluation
            }
        }

        val targetVer = fallbackLatestVersion ?: currentVersion
        val hasUpdate = compareSemanticVersions(currentVersion, targetVer) < 0
        return AppVersionInfo(
            currentVersion = currentVersion,
            latestVersion = targetVer,
            hasUpdate = hasUpdate,
            releaseNotes = null,
            downloadUrl = fallbackDownloadUrl,
        )
    }
}
