package top.wkbin.zaomeng.data.update

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import top.wkbin.zaomeng.BuildConfig
import java.util.Locale

data class AppUpdateInfo(
    val version: String,
    val downloadUrl: String,
    val fileName: String,
    val releaseNotes: String,
)

class AppUpdateRepository(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    suspend fun checkForUpdate(): AppUpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Zaomeng-Android")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("检查更新失败：GitHub 返回 ${response.code}")
            val payload = response.body?.string().orEmpty()
            val release = json.parseToJsonElement(payload).jsonObject
            val remoteVersion = release["tag_name"]?.jsonPrimitive?.content.orEmpty()
            if (!isNewerVersion(remoteVersion, BuildConfig.VERSION_NAME)) return@use null
            val assets = release["assets"]?.jsonArray.orEmpty()
            val asset = assets.map { it.jsonObject }
                .firstOrNull { asset ->
                    val name = asset["name"]?.jsonPrimitive?.content.orEmpty().lowercase(Locale.ROOT)
                    name.endsWith(".apk") && "arm64-v8a" in name
                }
                ?: assets.map { it.jsonObject }.firstOrNull { asset ->
                    asset["name"]?.jsonPrimitive?.content.orEmpty()
                        .lowercase(Locale.ROOT)
                        .endsWith(".apk")
                }
                ?: error("最新版本未提供 Android APK")
            AppUpdateInfo(
                version = remoteVersion.removePrefix("v"),
                downloadUrl = asset["browser_download_url"]?.jsonPrimitive?.content
                    ?: error("更新包下载地址无效"),
                fileName = asset["name"]?.jsonPrimitive?.content ?: "zaomeng-$remoteVersion.apk",
                releaseNotes = release["body"]?.jsonPrimitive?.content.orEmpty().trim(),
            )
        }
    }

    fun download(update: AppUpdateInfo): Long {
        val request = DownloadManager.Request(android.net.Uri.parse(update.downloadUrl))
            .setTitle("早梦 ${update.version}")
            .setDescription("下载完成后点击系统通知安装更新")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, update.fileName)
        return (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    }

    private companion object {
        const val RELEASE_URL = "https://api.github.com/repos/wkbin/zaomeng/releases/latest"
        val json = Json { ignoreUnknownKeys = true }
    }
}

internal fun isNewerVersion(remote: String, current: String): Boolean {
    fun components(value: String): List<Int>? {
        val normalized = value.trim().removePrefix("v").substringBefore('-')
        if (normalized.isBlank()) return null
        return normalized.split('.').map { part -> part.toIntOrNull() ?: return null }
    }
    val remoteParts = components(remote) ?: return false
    val currentParts = components(current) ?: return false
    val length = maxOf(remoteParts.size, currentParts.size)
    repeat(length) { index ->
        val difference = remoteParts.getOrElse(index) { 0 }.compareTo(currentParts.getOrElse(index) { 0 })
        if (difference != 0) return difference > 0
    }
    return false
}
