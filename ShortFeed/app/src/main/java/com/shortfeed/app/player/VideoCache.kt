package com.shortfeed.app.player

import android.content.Context
import android.webkit.CookieManager
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.shortfeed.app.data.Platform
import java.io.File

/**
 * Per-player header escalation state.
 *
 * Douyin answers 403 to a play request for reasons that are not always the same (missing web
 * cookies, a UA it dislikes, an unexplained referer). Rather than guessing once, each player keeps
 * an attempt counter: on a bad HTTP status the player re-prepares and the data source factory
 * reads the next header profile. No player rebuild, so the surface never blinks.
 */
class ProfileStore {
    @Volatile
    var attempt: Int = 0

    companion object {
        /** Four profiles: desktop/mobile UA × with/without douyin.com cookies. */
        const val MAX_ATTEMPT = 3
    }
}

/**
 * Process-wide disk cache for video data.
 *
 * Everything played through the app goes through a [CacheDataSource], so already-watched segments
 * are served from flash instead of the network: scrolling back is instant and free, and short
 * videos are effectively pre-downloaded while they play. Capped at 500 MB, LRU-evicted.
 *
 * One [SimpleCache] per process is mandatory — it locks its directory, a second instance on the
 * same folder would crash.
 */
object VideoCache {

    private const val MAX_BYTES = 500L * 1024 * 1024
    private var cache: SimpleCache? = null

    @Synchronized
    fun get(context: Context): SimpleCache = cache ?: run {
        val dir = File(context.externalCacheDir ?: context.cacheDir, "video_cache")
        SimpleCache(dir, LeastRecentlyUsedCacheEvictor(MAX_BYTES), StandaloneDatabaseProvider(context))
            .also { cache = it }
    }

    fun dataSourceFactory(
        context: Context,
        platform: Platform,
        store: ProfileStore,
    ): DataSource.Factory = DataSource.Factory {
        val attempt = store.attempt
        val headers = LinkedHashMap<String, String>()
        // 0: desktop + cookies, 1: mobile + cookies, 2: desktop no-cookie, 3: mobile no-cookie.
        val ua = if (attempt == 1 || attempt == 3) MOBILE_UA else DESKTOP_UA
        if (attempt < 3) headers.putAll(refererFor(platform))
        if (platform == Platform.DOUYIN && attempt < 2) {
            // The web feed's play endpoint needs the page's session cookies. But those cookies
            // must NOT follow the 302 to a CDN host: some nodes reject cross-domain Cookie/Origin
            // with 403. Later profiles therefore drop both and keep only the browser-like Referer.
            douyinCookie()?.let { headers["Cookie"] = it }
            headers["Origin"] = "https://www.douyin.com"
        }

        val upstream = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)

        CacheDataSource.Factory()
            .setCache(get(context.applicationContext))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()
    }

    /** Cookies of the logged-in Douyin web page; empty when not logged in. */
    private fun douyinCookie(): String? = runCatching {
        CookieManager.getInstance().getCookie("https://www.douyin.com")
    }.getOrNull()?.takeIf { it.isNotBlank() }

    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    const val MOBILE_UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

    fun refererFor(platform: Platform): Map<String, String> = when (platform) {
        Platform.BILIBILI -> mapOf("Referer" to "https://www.bilibili.com")
        Platform.DOUYIN -> mapOf("Referer" to "https://www.douyin.com")
        Platform.KUAISHOU -> mapOf("Referer" to "https://www.kuaishou.com")
    }
}
