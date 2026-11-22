package com.shortfeed.app.data.fixture

import android.content.Context
import com.shortfeed.app.data.PageResult
import com.shortfeed.app.data.Platform
import com.shortfeed.app.data.VideoItem
import com.shortfeed.app.data.VideoSource
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ships 6 real Douyin aweme records inside the APK (assets/douyin_fixtures.json, captured from
 * the zyronon/douyin sample set) so the user can install and immediately play real Douyin video
 * in the immersive player — no backend, no signing, first-run proof that the player path works.
 *
 * These are frozen samples: their CDN play URLs were verified to still stream (HTTP 206, ftyp/isom)
 * at the time of packaging. When they eventually expire, wire up the backend (BackendSource) for
 * live Douyin. The field mapping below mirrors backend/main.py:_normalize_douyin so the two paths
 * stay in sync.
 */
class FixtureSource(private val context: Context) : VideoSource {

    override val key: String = "douyin_fixture"
    override val platform: Platform = Platform.DOUYIN

    private val items: List<VideoItem> by lazy { parse() }

    override suspend fun nextPage(cursor: String?): PageResult {
        // One page of 6, then exhausted. Enough to prove the player; live feed comes from backend.
        if (cursor != null) return PageResult(emptyList(), null)
        return PageResult(items, null)
    }

    private fun parse(): List<VideoItem> {
        val raw = runCatching {
            context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<VideoItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val playUrl = PLAY_URL_PATHS.firstNotNullOfOrNull { walk(o, it) } ?: continue
            out += VideoItem(
                id = walk(o, "aweme_id") ?: walk(o, "id") ?: i.toString(),
                platform = platform,
                title = walk(o, "desc") ?: walk(o, "title") ?: "",
                author = walk(o, "author.nickname") ?: walk(o, "nickname") ?: "",
                cover = walk(o, "video.cover.url_list.0") ?: walk(o, "cover") ?: "",
                playUrl = playUrl,
                sourceKey = key,
            )
        }
        return out
    }

    /** Walk a dot-path with list-index support (e.g. "video.play_addr.url_list.0"). */
    private fun walk(obj: JSONObject, path: String): String? {
        var cur: Any? = obj
        for (part in path.split(".")) {
            cur = when (cur) {
                is JSONObject -> cur.opt(part)
                is JSONArray -> part.toIntOrNull()
                    ?.takeIf { it in 0 until cur.length() }
                    ?.let { cur.opt(it) }
                else -> null
            }
            if (cur == null || cur == JSONObject.NULL) return null
        }
        return (cur as? String)?.takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val ASSET_NAME = "douyin_fixtures.json"
        val PLAY_URL_PATHS = listOf(
            "video.play_addr.url_list.0",
            "video.play_addr_h264.url_list.0",
            "video.bit_rate.0.play_addr.url_list.0",
        )
    }
}
