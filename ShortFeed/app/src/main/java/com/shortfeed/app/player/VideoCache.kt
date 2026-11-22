package com.shortfeed.app.player

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.shortfeed.app.data.Platform
import java.io.File

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

    /** HTTP upstream with the headers each platform's CDN demands, wrapped in the disk cache. */
    fun dataSourceFactory(context: Context, platform: Platform): DataSource.Factory {
        val upstream = DefaultHttpDataSource.Factory()
            .setUserAgent(DESKTOP_UA) // mobile & ExoPlayer default UAs get 403 from these CDNs
            .setDefaultRequestProperties(refererFor(platform))
        return CacheDataSource.Factory()
            .setCache(get(context.applicationContext))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    fun refererFor(platform: Platform): Map<String, String> = when (platform) {
        Platform.BILIBILI -> mapOf("Referer" to "https://www.bilibili.com")
        Platform.DOUYIN -> mapOf("Referer" to "https://www.douyin.com")
        Platform.KUAISHOU -> mapOf("Referer" to "https://www.kuaishou.com")
    }
}
