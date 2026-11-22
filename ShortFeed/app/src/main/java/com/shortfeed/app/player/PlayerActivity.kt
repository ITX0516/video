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
import com.shortfeed.app.data.douyin.DouyinWebBridge
import com.shortfeed.app.data.douyin.DouyinWebSource
import com.shortfeed.app.data.fixture.FixtureSource

/**
 * Hosts the zero-chrome player. Source switching is hardware-driven (volume keys) so the screen
 * stays 100% video.
 */
class PlayerActivity : ComponentActivity() {

    private lateinit var repository: FeedRepository
    private var douyinBridge: DouyinWebBridge? = null

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

        // The Douyin bridge WebView sits behind the (opaque) Compose surface.
        douyinBridge?.attachBehind()
    }

    override fun onResume() {
        super.onResume()
        douyinBridge?.onResume()
    }

    override fun onPause() {
        douyinBridge?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        douyinBridge?.destroy()
        douyinBridge = null
        super.onDestroy()
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
            if (baseUrl.isNotEmpty()) {
                // Explicit companion backend → live feed from the self-hosted service.
                list += BackendSource(KEY_DOUYIN, Platform.DOUYIN, baseUrl, KEY_DOUYIN)
            } else {
                // No backend → use the account's real feed via the logged-in web session. If the
                // page can't be read, fall back to the 6 bundled samples so the screen isn't blank.
                val bridge = douyinBridge ?: DouyinWebBridge(this).also { douyinBridge = it }
                list += DouyinWebSource(bridge, FixtureSource(this))
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
