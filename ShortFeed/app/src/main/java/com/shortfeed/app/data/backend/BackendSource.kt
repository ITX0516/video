package com.shortfeed.app.data.backend

import com.shortfeed.app.data.PageResult
import com.shortfeed.app.data.Platform
import com.shortfeed.app.data.VideoItem
import com.shortfeed.app.data.VideoSource
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Douyin / Kuaishou source backed by a self-hosted companion service.
 *
 * WHY A BACKEND AND NOT ON-DEVICE SIGNING:
 * Douyin (a_bogus / msToken / X-Bogus) and Kuaishou (__NS_sig3 / sig) rotate their signature
 * schemes periodically. If the signing JS lived inside this APK, every algorithm update would
 * force a rebuild + reinstall. By delegating to a thin backend, a break is fixed in one place
 * (the server) and the installed app keeps working. For personal use this backend can run on a
 * cheap VPS, a home NAS, or even Termux on the same phone.
 *
 * CONTRACT (your backend must implement):
 *   GET {baseUrl}/feed?platform={douyin|kuaishou}&cursor={cursor}
 *   -> 200 {
 *        "items": [ { "id", "title", "author", "cover", "play_url" } ],
 *        "next_cursor": "opaque-string" | null
 *      }
 *
 * See backend/main.py in this repo for a reference implementation that wraps the maintained
 * self-hostable engine (Douyin_TikTok_Download_API) so you get identity-pool scheduling,
 * circuit breakers and a browser-RPC signing fallback for free.
 */
class BackendSource(
    override val key: String,
    override val platform: Platform,
    private val baseUrl: String,
    private val platformParam: String,
) : VideoSource {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun nextPage(cursor: String?): PageResult {
        val trimmed = baseUrl.trimEnd('/')
        val url = buildString {
            append(trimmed)
            append("/feed?platform=").append(platformParam)
            if (!cursor.isNullOrEmpty()) append("&cursor=").append(cursor)
        }
        val request = Request.Builder().url(url)
            .header("User-Agent", "ShortFeed/1.0")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return PageResult(emptyList(), null)
            val body = resp.body?.string().orEmpty()
            val obj = JSONObject(body)
            val arr = obj.optJSONArray("items") ?: return PageResult(emptyList(), null)
            val items = ArrayList<VideoItem>(arr.length())
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val playUrl = it.optString("play_url")
                if (playUrl.isEmpty()) continue
                items += VideoItem(
                    id = it.optString("id"),
                    platform = platform,
                    title = it.optString("title"),
                    author = it.optString("author"),
                    cover = it.optString("cover"),
                    playUrl = playUrl,
                    sourceKey = key,
                )
            }
            val next = if (obj.isNull("next_cursor")) null else obj.optString("next_cursor").takeIf { it.isNotEmpty() }
            return PageResult(items, next)
        }
    }
}
