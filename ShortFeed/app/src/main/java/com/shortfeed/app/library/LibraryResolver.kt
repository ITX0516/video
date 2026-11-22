package com.shortfeed.app.library

import android.content.Context
import com.shortfeed.app.Diagnostics
import com.shortfeed.app.Prefs
import com.shortfeed.app.data.Platform
import com.shortfeed.app.data.bilibili.BiliSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * Resolves a *fresh* play address for a saved like/favourite.
 *
 * Saved entries are identified by platform + video id, not by the CDN URL captured at like time:
 * those URLs expire (Bilibili's within hours), so a library built on them rots. Bilibili is
 * re-resolved through its API. Douyin has no unsigned per-video API, so we make a best-effort
 * attempt at the public share page and otherwise fall back to the address saved with the entry.
 */
object LibraryResolver {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun resolve(
        context: Context,
        platform: Platform,
        id: String,
        fallbackUrl: String,
    ): String = withContext(Dispatchers.IO) {
        val fresh = runCatching {
            when (platform) {
                Platform.BILIBILI -> {
                    val prefs = Prefs(context.applicationContext)
                    BiliSource(prefs.loadBiliSession(), prefs.loadBiliQuality()).resolveById(id)
                }
                Platform.DOUYIN -> resolveDouyinShare(id)
                Platform.KUAISHOU -> null
            }
        }.onFailure {
            Diagnostics.record(platform.name, id, "重新解析异常: ${it.javaClass.simpleName}")
        }.getOrNull()

        if (!fresh.isNullOrEmpty()) return@withContext fresh
        if (fallbackUrl.isNotEmpty()) {
            Diagnostics.record(platform.name, id, "重新解析失败，退回保存时的地址（可能已过期）")
            return@withContext fallbackUrl
        }
        Diagnostics.record(platform.name, id, "重新解析失败且无保存地址")
        ""
    }

    /** Best effort: the public share page embeds the play address without any signing. */
    private fun resolveDouyinShare(awemeId: String): String? {
        val request = Request.Builder()
            .url("https://www.iesdouyin.com/share/video/$awemeId/")
            .header("User-Agent", MOBILE_UA)
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val html = resp.body?.string().orEmpty()
            // The payload is URL-encoded JSON inside RENDER_DATA / _ROUTER_DATA.
            val decoded = runCatching { URLDecoder.decode(html, "UTF-8") }.getOrDefault(html)
            val match = PLAY_ADDR_REGEX.find(decoded) ?: return null
            return match.groupValues[1]
                .replace("\\u002F", "/")
                .replace("\\/", "/")
                .takeIf { it.startsWith("http") }
        }
    }

    private val PLAY_ADDR_REGEX =
        Regex("\"play_addr\"\\s*:\\s*\\{[^{}]*\"url_list\"\\s*:\\s*\\[\\s*\"([^\"]+)\"")

    private const val MOBILE_UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
}
