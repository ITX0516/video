package com.shortfeed.app.data.douyin

import com.shortfeed.app.data.PageResult
import com.shortfeed.app.data.Platform
import com.shortfeed.app.data.VideoSource

/**
 * Douyin feed backed by [DouyinWebBridge] — the account's own recommendation stream, played by the
 * native player. Cursor never exhausts: the page keeps loading more as we scroll it in the
 * background, so the immersive feed can go on indefinitely.
 *
 * If the bridge yields nothing (page failed to load, not logged in, Douyin changed its frontend),
 * the bundled [fallback] samples are served once so the app is never a blank screen.
 */
class DouyinWebSource(
    private val bridge: DouyinWebBridge,
    private val fallback: VideoSource? = null,
) : VideoSource {

    override val key: String = DouyinWebBridge.KEY
    override val platform: Platform = Platform.DOUYIN

    private var usedFallback = false
    private var servedItems = 0

    override suspend fun nextPage(cursor: String?): PageResult {
        // First load waits a bit for the page boot; later pages only take what's already buffered so
        // swiping never stalls waiting for a network round-trip.
        val timeout = if (servedItems == 0) FIRST_TIMEOUT_MS else LATER_TIMEOUT_MS
        val batch = bridge.takeBatch(timeout, max = 10)
        if (batch.isNotEmpty()) {
            servedItems += batch.size
            return PageResult(batch, "more")
        }
        if (!usedFallback) {
            usedFallback = true
            val fb = fallback?.nextPage(null)
            if (fb != null && fb.items.isNotEmpty()) {
                servedItems += fb.items.size
                return PageResult(fb.items, "more")
            }
        }
        return PageResult(emptyList(), "more")
    }

    private companion object {
        const val FIRST_TIMEOUT_MS = 15_000L
        const val LATER_TIMEOUT_MS = 6_000L
    }
}
