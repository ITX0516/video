package com.shortfeed.app.library

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.shortfeed.app.data.Platform

/** Minimal full-screen playback for one saved library item. */
class LibraryPlayerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isEmpty()) {
            finish()
            return
        }
        val platform = runCatching {
            Platform.valueOf(intent.getStringExtra(EXTRA_PLATFORM) ?: Platform.BILIBILI.name)
        }.getOrDefault(Platform.BILIBILI)

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(DESKTOP_UA)
            .setDefaultRequestProperties(mapOf("Referer" to refererFor(platform)))
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                playWhenReady = true
            }

        setContent {
            DisposableEffect(Unit) {
                onDispose { player.release() }
            }
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            this.player = player
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_PLATFORM = "platform"
        const val EXTRA_TITLE = "title"

        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private fun refererFor(platform: Platform): String = when (platform) {
            Platform.BILIBILI -> "https://www.bilibili.com"
            Platform.DOUYIN -> "https://www.douyin.com"
            Platform.KUAISHOU -> "https://www.kuaishou.com"
        }
    }
}
