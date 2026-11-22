package com.shortfeed.app.data

enum class Platform { BILIBILI, DOUYIN, KUAISHOU }

/** One selectable rendition of a video, e.g. "720P" → its direct URL. */
data class QualityVariant(val label: String, val url: String)

/**
 * One playable video, normalized across all sources.
 * The player only ever sees this shape — platform-specific quirks stay inside each [VideoSource].
 */
data class VideoItem(
    val id: String,
    val platform: Platform,
    val title: String,
    val author: String,
    val cover: String,
    /** Already-resolved direct video URL. Empty when the source resolves lazily via [playUrlResolver]. */
    val playUrl: String,
    val sourceKey: String,
    /** Extra renditions the platform offered, highest first. Empty when the source has only one. */
    val variants: List<QualityVariant> = emptyList(),
    /**
     * Optional lazy resolver for the direct video URL. Used when a source prefers not to resolve
     * every item on a page up-front (e.g. signed CDN URLs expire, and resolving all 20 at once is
     * wasteful and risks rate-limit). The repository resolves current + next-ahead on demand.
     */
    val playUrlResolver: (suspend () -> String?)? = null,
)

/** A page of feed items plus an opaque cursor for the next page (null = no more). */
data class PageResult(
    val items: List<VideoItem>,
    val nextCursor: String?,
)

/**
 * Pluggable content source. Adding a platform = implementing this one interface.
 * The player and repository never know which platform they are talking to.
 */
interface VideoSource {
    val key: String
    val platform: Platform
    suspend fun nextPage(cursor: String?): PageResult
}
