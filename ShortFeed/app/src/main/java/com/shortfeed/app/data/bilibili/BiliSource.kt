package com.shortfeed.app.data.bilibili

import com.shortfeed.app.data.PageResult
import com.shortfeed.app.data.Platform
import com.shortfeed.app.data.VideoItem
import com.shortfeed.app.data.VideoSource
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Bilibili recommendation feed, signed on-device with WBI (see [WbiSigner]).
 *
 * This source works with no backend and no login: it mints an anonymous guest session
 * (buvid3) with a single plain HTTP call, exactly as the self-hosted reference backends do.
 * WBI keys are cached and refreshed on risk-control responses, so daily key rotation is handled
 * automatically.
 */
class BiliSource(private val sessdata: String = "") : VideoSource {

    override val key: String = "bilibili"
    override val platform: Platform = Platform.BILIBILI

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var imgKey: String? = null
    private var subKey: String? = null
    private var keysAt: Long = 0L
    private var buvid3: String = ""

    private fun cookie(): String = buildList {
        if (buvid3.isNotEmpty()) add("buvid3=$buvid3")
        if (sessdata.isNotEmpty()) add("SESSDATA=$sessdata")
    }.joinToString("; ")

    private fun get(path: String, signed: Boolean = false, params: Map<String, String> = emptyMap()): JSONObject {
        val query = if (signed) WbiSigner.sign(params, imgKey!!, subKey!!) else buildQuery(params)
        val request = Request.Builder()
            .url("$path${if (query.isEmpty()) "" else "?$query"}")
            .header("User-Agent", UA)
            .header("Referer", "https://www.bilibili.com")
            .apply { if (cookie().isNotEmpty()) header("Cookie", cookie()) }
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            return JSONObject(text)
        }
    }

    /** GET with one automatic key/session refresh on a risk-control response. */
    private fun signedGetWithRetry(url: String, params: Map<String, String>): JSONObject {
        ensureKeys()
        ensureBuvid()
        val obj = get(url, signed = true, params = params)
        val code = obj.optInt("code", -1)
        if (code in RISK_CODES) {
            refreshKeysAndBuvid()
            return get(url, signed = true, params = params)
        }
        return obj
    }

    private fun ensureKeys() {
        if (imgKey != null && subKey != null && System.currentTimeMillis() - keysAt < KEY_TTL_MS) return
        refreshKeysAndBuvid()
    }

    private fun ensureBuvid() {
        if (buvid3.isNotEmpty()) return
        refreshKeysAndBuvid()
    }

    private fun refreshKeysAndBuvid() {
        runCatching {
            val nav = get("https://api.bilibili.com/x/web-interface/nav")
            val wbi = nav.optJSONObject("data")?.optJSONObject("wbi_img")
            imgKey = wbi?.optString("img_url")?.let(::keyFromUrl)
            subKey = wbi?.optString("sub_url")?.let(::keyFromUrl)
            keysAt = System.currentTimeMillis()
        }
        if (buvid3.isEmpty()) runCatching {
            val spi = get("https://api.bilibili.com/x/frontend/finger/spi")
            buvid3 = spi.optJSONObject("data")?.optString("b_3").orEmpty()
        }
    }

    override suspend fun nextPage(cursor: String?): PageResult {
        val idx = cursor?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val params = linkedMapOf(
            "ps" to "20",
            "fresh_type" to "3",
            "feed_version" to "V8",
            "fresh_idx" to idx.toString(),
            "fetch_row" to "1",
            "refresh" to "1",
            "web_location" to "1430650",
        )
        val obj = signedGetWithRetry("https://api.bilibili.com/x/web-interface/wbi/index/top/feed/rcmd", params)
        val data = obj.optJSONObject("data") ?: return PageResult(emptyList(), null)
        val arr = data.optJSONArray("item") ?: return PageResult(emptyList(), null)

        val items = ArrayList<VideoItem>(arr.length())
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            val bvid = it.optString("bvid")
            val cid = it.optLong("cid")
            if (bvid.isEmpty() || cid == 0L) continue
            // Do NOT resolve the play URL here: the signed CDN URL expires and resolving all 20 at
            // once is slow and risks rate-limiting. Resolve lazily when the video is up next.
            items += VideoItem(
                id = bvid,
                platform = Platform.BILIBILI,
                title = it.optString("title"),
                author = it.optJSONObject("owner")?.optString("name").orEmpty(),
                cover = it.optString("pic"),
                playUrl = "",
                sourceKey = key,
                playUrlResolver = { resolvePlayUrl(bvid, cid) },
            )
        }
        val nextIdx = data.optInt("fresh_idx", 0).takeIf { it > 0 }?.toString() ?: (idx + 1).toString()
        return PageResult(items, nextIdx)
    }

    private fun resolvePlayUrl(bvid: String, cid: Long): String? {
        val params = mapOf(
            "bvid" to bvid,
            "cid" to cid.toString(),
            "qn" to "64",        // 720P
            "fnval" to "0",      // single MP4 (durl) — simplest for a direct video URL
            "fnver" to "0",
            "fourk" to "1",
        )
        val obj = signedGetWithRetry("https://api.bilibili.com/x/player/wbi/playurl", params)
        val data = obj.optJSONObject("data") ?: return null
        // Preferred: a single progressive MP4 with audio+video muxed.
        val durl = data.optJSONArray("durl")
        if (durl != null && durl.length() > 0) {
            val url = durl.optJSONObject(0)?.optString("url").orEmpty()
            if (url.isNotEmpty()) return url
        }
        // Fallback: DASH video track (may be video-only; acceptable last resort).
        val dash = data.optJSONObject("dash")
        val video = dash?.optJSONArray("video")
        if (video != null && video.length() > 0) {
            return video.optJSONObject(0)?.optString("baseUrl")?.takeIf { it.isNotEmpty() }
        }
        return null
    }

    companion object {
        private const val UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private const val KEY_TTL_MS = 50 * 60 * 1000L // refresh keys before their ~daily rollover
        private val RISK_CODES = setOf(-352, -403, 1203, 412, -799)

        private fun keyFromUrl(url: String): String =
            url.substringAfterLast('/').substringBefore('.')

        private fun buildQuery(params: Map<String, String>): String =
            params.entries.joinToString("&") { (k, v) -> "$k=$v" }
    }
}
