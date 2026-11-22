package com.shortfeed.app.player

import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.shortfeed.app.data.FeedRepository
import com.shortfeed.app.data.Platform
import com.shortfeed.app.data.VideoItem

/**
 * The entire in-app surface: a full-screen vertical pager of videos.
 * No persistent chrome — no titles, no buttons, no progress bar. Swipe up/down to change videos.
 */
@Composable
fun PlayerScreen(repository: FeedRepository) {
    val items by repository.items.collectAsStateWithLifecycle()
    val activeKey by repository.activeKey.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(pageCount = { items.size })

    // (Re)load the active source whenever it changes (volume-key switching) — includes first load.
    LaunchedEffect(activeKey) { repository.loadMore() }

    // On page change: pre-resolve the next few videos' URLs, and load more feed near the end.
    LaunchedEffect(pagerState.currentPage, items.size) {
        repository.prefetch(pagerState.currentPage, lookahead = 2)
        if (items.isNotEmpty() && pagerState.currentPage >= items.size - 3) {
            repository.loadMore()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        when {
            items.isEmpty() -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
            else -> VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1, // keep the adjacent page composed for seamless swipe
            ) { page ->
                items.getOrNull(page)?.let { item ->
                    VideoPage(item = item, active = page == pagerState.currentPage, repository = repository)
                }
            }
        }
    }
}

@Composable
private fun VideoPage(item: VideoItem, active: Boolean, repository: FeedRepository) {
    val context = LocalContext.current
    var playUrl by remember(item.id) { mutableStateOf(item.playUrl) }

    // One player per page, with the request headers each platform's CDN demands.
    val player = remember(item.id) {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(DESKTOP_UA) // mobile & ExoPlayer default UAs get 403 from these CDNs
            .setDefaultRequestProperties(refererFor(item.platform))
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
    }

    DisposableEffect(item.id) {
        onDispose { player.release() }
    }

    // Resolve the direct URL lazily (cached by the repository) when this page becomes active.
    LaunchedEffect(item.id, active) {
        if (active && playUrl.isEmpty()) {
            val url = repository.resolvePlayUrl(item)
            if (url.isNotEmpty()) playUrl = url
        }
    }

    // Attach media once a URL is known, and (re)start/pause with page activity.
    LaunchedEffect(playUrl, active) {
        if (playUrl.isEmpty()) return@LaunchedEffect
        if (player.mediaItemCount == 0) {
            player.setMediaItem(MediaItem.fromUri(playUrl))
            player.prepare()
        }
        player.playWhenReady = active
        if (active) player.play()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false // <-- the "no UI" guarantee
                    // FIT = show the WHOLE frame (letterboxed if needed). ZOOM cropped the sides
                    // of 9:16 videos on tall phones, which read as "picture incomplete".
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

private fun refererFor(platform: Platform): Map<String, String> = when (platform) {
    Platform.BILIBILI -> mapOf("Referer" to "https://www.bilibili.com")
    Platform.DOUYIN -> mapOf("Referer" to "https://www.douyin.com")
    Platform.KUAISHOU -> mapOf("Referer" to "https://www.kuaishou.com")
}
