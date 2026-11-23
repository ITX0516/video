package com.shortfeed.app.library

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.shortfeed.app.Diagnostics
import com.shortfeed.app.data.Platform
import com.shortfeed.app.player.ProfileStore
import com.shortfeed.app.player.VideoCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Full-screen playback of one saved like/favourite.
 *
 * The entry is identified by platform + video id and the address is resolved fresh on open
 * (see [LibraryResolver]); the URL stored with the entry is only a fallback.
 */
class LibraryPlayerActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: ExoPlayer? = null
    private val playerState = mutableStateOf<ExoPlayer?>(null)
    private val message = mutableStateOf<String?>("正在重新解析播放地址…")
    private var videoId = ""
    private var platform = Platform.BILIBILI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        videoId = intent.getStringExtra(EXTRA_ID).orEmpty()
        val fallbackUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        platform = runCatching {
            Platform.valueOf(intent.getStringExtra(EXTRA_PLATFORM) ?: Platform.BILIBILI.name)
        }.getOrDefault(Platform.BILIBILI)
        if (videoId.isEmpty() && fallbackUrl.isEmpty()) {
            finish()
            return
        }

        setContent {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                playerState.value?.let { p ->
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                this.player = p
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                message.value?.let { text ->
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(text, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { finish() }) { Text("返回", color = Color(0xFFD0BCFF)) }
                    }
                }
            }
        }

        scope.launch {
            val url = LibraryResolver.resolve(applicationContext, platform, videoId, fallbackUrl)
            if (url.isEmpty()) {
                message.value = "无法获取播放地址\n视频可能已下架，或保存的地址已过期"
            } else {
                startPlayback(url)
            }
        }
    }

    private fun startPlayback(url: String) {
        message.value = "加载中…"
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    VideoCache.dataSourceFactory(this, platform, ProfileStore()),
                ),
            )
            .build()
        p.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                message.value = null
            }

            override fun onPlayerError(error: PlaybackException) {
                Diagnostics.record(platform.name, videoId, "收藏播放失败 ${error.errorCodeName}")
                message.value = "播放失败：${error.errorCodeName}\n地址可能已过期"
            }
        })
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.playWhenReady = true
        player = p
        playerState.value = p
    }

    override fun onDestroy() {
        scope.cancel()
        player?.release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_URL = "url"
        const val EXTRA_PLATFORM = "platform"
        const val EXTRA_TITLE = "title"
    }
}
