package com.shortfeed.app.data

enum class Platform { BILIBILI, DOUYIN, KUAISHOU }

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
    val playUrl: String,
    val sourceKey: String,
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
