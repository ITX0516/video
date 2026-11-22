package com.shortfeed.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny in-memory ring buffer of playback/resolution failures.
 *
 * Bug reports so far have been screenshots of a black screen. Instead, every failure is recorded
 * here with its platform, video id and error code, and can be copied as text from the diagnostics
 * page. Nothing leaves the device unless the user pastes it somewhere.
 */
object Diagnostics {

    data class Entry(val at: Long, val platform: String, val id: String, val message: String)

    private const val MAX_ENTRIES = 50
    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun record(platform: String, id: String, message: String) {
        entries.addLast(Entry(System.currentTimeMillis(), platform, id, message))
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList().asReversed() // newest first

    @Synchronized
    fun clear() = entries.clear()

    fun format(versionName: String): String {
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        val body = snapshot().joinToString("\n") { e ->
            "${fmt.format(Date(e.at))}  ${e.platform}  ${e.id}  ${e.message}"
        }
        return "ShortFeed $versionName\n" + (body.ifEmpty { "（暂无失败记录）" })
    }
}
