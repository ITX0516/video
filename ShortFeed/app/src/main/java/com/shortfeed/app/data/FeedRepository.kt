package com.shortfeed.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds the unified feed for the current active source.
 *
 * Design intent: the Android APK is a thin client. Signing and anti-bot logic live in each
 * [VideoSource]. For Bilibili the signing is done on-device in pure Kotlin; for Douyin / Kuaishou
 * it is delegated to a self-hosted backend so a signature update only touches the server, never
 * the installed APK (see [com.shortfeed.app.data.backend.BackendSource]).
 */
class FeedRepository(private val sources: List<VideoSource>) {

    private val _items = MutableStateFlow<List<VideoItem>>(emptyList())
    val items: StateFlow<List<VideoItem>> = _items.asStateFlow()

    private val _activeKey = MutableStateFlow(sources.firstOrNull()?.key ?: "")
    val activeKey: StateFlow<String> = _activeKey.asStateFlow()

    private val cursors = mutableMapOf<String, String?>()

    /** Cache of lazily-resolved direct video URLs, keyed by item id. */
    private val resolvedUrls = ConcurrentHashMap<String, String>()

    private var activeIndex: Int = 0
        set(value) {
            field = value.coerceIn(0, (sources.size - 1).coerceAtLeast(0))
        }
    val activeSource: VideoSource?
        get() = sources.getOrNull(activeIndex)

    /** Switch to the next/previous enabled source and reset the feed. Volume keys drive this. */
    fun cycleActive(delta: Int) {
        if (sources.size <= 1) return
        activeIndex = (activeIndex + delta).mod(sources.size)
        _items.value = emptyList()
        cursors.clear()
        resolvedUrls.clear()
        _activeKey.value = activeSource?.key ?: ""
    }

    suspend fun refresh() {
        _items.value = emptyList()
        cursors.clear()
        resolvedUrls.clear()
        loadMore()
    }

    /** Append the next page of the active source. Safe to call repeatedly; no-ops when exhausted. */
    suspend fun loadMore() {
        val src = activeSource ?: return
        if (cursors.containsKey(src.key) && cursors[src.key] == CURSOR_EXHAUSTED) return
        val cursor = cursors[src.key]
        val page = withContext(Dispatchers.IO) { runCatching { src.nextPage(cursor) }.getOrNull() }
            ?: PageResult(emptyList(), CURSOR_EXHAUSTED)
        cursors[src.key] = page.nextCursor ?: CURSOR_EXHAUSTED
        _items.value = _items.value + page.items
    }

    /**
     * Returns a playable URL for [item], resolving lazily via [VideoItem.playUrlResolver] when
     * the source did not pre-fill [VideoItem.playUrl]. Results are cached for the session.
     */
    suspend fun resolvePlayUrl(item: VideoItem): String {
        resolvedUrls[item.id]?.let { return it }
        if (item.playUrl.isNotEmpty()) return item.playUrl
        val url = withContext(Dispatchers.IO) {
            runCatching { item.playUrlResolver?.invoke() }.getOrNull()
        }.orEmpty()
        if (url.isNotEmpty()) resolvedUrls[item.id] = url
        return url
    }

    /** Warm the URL cache for the next few videos so swipes don't block on resolution. */
    suspend fun prefetch(currentPage: Int, lookahead: Int = 2) {
        val list = _items.value
        for (i in 1..lookahead) {
            list.getOrNull(currentPage + i)?.let { resolvePlayUrl(it) }
        }
    }

    companion object {
        private const val CURSOR_EXHAUSTED = "__exhausted__"
    }
}
