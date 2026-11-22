package com.shortfeed.app.data.bilibili

import java.security.MessageDigest

/**
 * Pure-Kotlin implementation of Bilibili's WBI (Web Interface) request signature.
 *
 * Reference: https://github.com/SocialSisterYi/bilibili-API-collect/blob/master/docs/misc/sign/wbi.md
 * Verified against the official test vector:
 *   img_key = 7cd084941338484aae1ad9425b84077c
 *   sub_key = 4932caff0ff746eab6f01bf08b70ac45
 *   -> mixin_key = ea1db124af3c7062474693fa704f4ff8
 *
 * WBI is stable since 2023 (keys rotate ~daily, algorithm unchanged), which is why Bilibili is
 * the one source that can be signed on-device with no maintenance burden.
 */
object WbiSigner {

    private val MIXIN_KEY_ENC_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
        61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
        36, 20, 34, 44, 52,
    )

    /** Visible for testing. */
    fun mixinKey(imgKey: String, subKey: String): String {
        val raw = imgKey + subKey
        val sb = StringBuilder(MIXIN_KEY_ENC_TAB.size)
        for (i in MIXIN_KEY_ENC_TAB) {
            if (i in raw.indices) sb.append(raw[i])
        }
        if (sb.length > 32) sb.setLength(32)
        return sb.toString()
    }

    private fun md5Hex(s: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    /** encodeURIComponent semantics: uppercase hex, %20 for space, unreserved = A-Za-z0-9-_.~ */
    private fun encode(value: String): String {
        val sb = StringBuilder(value.length * 3)
        for (ch in value) {
            when {
                ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
                    ch == '-' || ch == '_' || ch == '.' || ch == '~' -> sb.append(ch)
                else -> for (b in ch.toString().toByteArray(Charsets.UTF_8)) {
                    sb.append(String.format("%%%02X", b))
                }
            }
        }
        return sb.toString()
    }

    /**
     * Returns the final query string (original params + &w_rid=...&wts=...) ready to append to a URL.
     * The signature itself is computed over the lexicographically sorted params + mixin_key.
     */
    fun sign(params: Map<String, String>, imgKey: String, subKey: String): String {
        val wts = (System.currentTimeMillis() / 1000).toString()
        val withTs = params.toMutableMap().apply { put("wts", wts) }
        val sortedQuery = withTs.toSortedMap().entries.joinToString("&") { (k, v) ->
            "${encode(k)}=${encode(filterValue(v))}"
        }
        val wRid = md5Hex(sortedQuery + mixinKey(imgKey, subKey))
        val original = params.entries.joinToString("&") { (k, v) ->
            "${encode(k)}=${encode(filterValue(v))}"
        }
        return if (original.isEmpty()) "w_rid=$wRid&wts=$wts" else "$original&w_rid=$wRid&wts=$wts"
    }

    private fun filterValue(v: String): String = v.filterNot { it in "!'()*" }
}
