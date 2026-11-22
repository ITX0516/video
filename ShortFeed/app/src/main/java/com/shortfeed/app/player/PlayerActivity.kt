package com.shortfeed.app.player

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shortfeed.app.data.FeedRepository
import com.shortfeed.app.data.Platform
import com.shortfeed.app.data.VideoSource
import com.shortfeed.app.data.backend.BackendSource
import com.shortfeed.app.data.bilibili.BiliSource
import com.shortfeed.app.data.fixture.FixtureSource

/**
 * Hosts the zero-chrome player. Source switching is hardware-driven (volume keys) so the screen
 * stays 100% video.
 */
class PlayerActivity : ComponentActivity() {

    private lateinit var repository: FeedRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val enabled = intent.getStringArrayListExtra(EXTRA_ENABLED) ?: arrayListOf(KEY_BILIBILI)
        val baseUrl = intent.getStringExtra(EXTRA_BACKEND_URL).orEmpty()
        repository = FeedRepository(buildSources(enabled, baseUrl))

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PlayerScreen(repository)
            }
        }
    }

    /** Volume up/down cycle the active source instead of changing volume. Truly UI-less switching. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    repository.cycleActive(1)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    repository.cycleActive(-1)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun buildSources(enabled: List<String>, baseUrl: String): List<VideoSource> {
        val list = mutableListOf<VideoSource>()
        if (KEY_BILIBILI in enabled) list += BiliSource()
        if (KEY_DOUYIN in enabled) {
            // Backend URL set → live feed via the companion service. Empty → play the 6 real
            // Douyin samples bundled in the APK (no backend needed; proves the player path).
            if (baseUrl.isNotEmpty()) {
                list += BackendSource(KEY_DOUYIN, Platform.DOUYIN, baseUrl, KEY_DOUYIN)
            } else {
                list += FixtureSource(this)
            }
        }
        if (KEY_KUAISHOU in enabled && baseUrl.isNotEmpty()) {
            list += BackendSource(KEY_KUAISHOU, Platform.KUAISHOU, baseUrl, KEY_KUAISHOU)
        }
        if (list.isEmpty()) list += BiliSource() // never empty
        return list
    }

    companion object {
        const val EXTRA_ENABLED = "enabled_sources"
        const val EXTRA_BACKEND_URL = "backend_url"
        const val KEY_BILIBILI = "bilibili"
        const val KEY_DOUYIN = "douyin"
        const val KEY_KUAISHOU = "kuaishou"
    }
}
