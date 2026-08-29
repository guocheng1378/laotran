package com.eta.laotrans

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * 应用内更新：访问 GitHub Releases(latest) 检测最新版，并下载安装（覆盖更新）。
 * 覆盖更新的前提是 CI 使用固定签名的 keystore（本仓库 KEYSTORE_P12）。
 */
object Updater {
    private const val REPO = "guocheng1378/laotran"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private val client = OkHttpClient.Builder().build()

    data class UpdateInfo(
        val latestVersion: String,
        val downloadUrl: String,
        val hasUpdate: Boolean,
        val notes: String = ""
    )

    suspend fun check(): UpdateInfo = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(API)
            .header("Accept", "application/vnd.github+json")
            .build()
        val resp = client.newCall(req).execute()
        val json = JSONObject(resp.body?.string().orEmpty())
        val tag = json.optString("tag_name", "")
        val latest = tag.removePrefix("v").trim()
        val notes = json.optString("body", "")
        var url = ""
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name", "").endsWith(".apk", true)) {
                    url = a.optString("browser_download_url", "")
                    break
                }
            }
        }
        UpdateInfo(latest, url, isNewer(latest, BuildConfig.VERSION_NAME), notes)
    }

    suspend fun downloadAndInstall(context: Context, url: String) {
        val file = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).build()
            val resp = client.newCall(req).execute()
            val out = File(context.externalCacheDir, "update.apk")
            out.outputStream().use { os -> resp.body?.byteStream()?.copyTo(os) }
            out
        }
        withContext(Dispatchers.Main) {
            install(context, file)
        }
    }

    private fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    private fun isNewer(remote: String, current: String): Boolean {
        val r = parse(remote)
        val c = parse(current)
        val n = maxOf(r.size, c.size)
        for (i in 0 until n) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }

    private fun parse(v: String): List<Int> =
        v.split(Regex("[^0-9]+")).mapNotNull { it.toIntOrNull() }
}
