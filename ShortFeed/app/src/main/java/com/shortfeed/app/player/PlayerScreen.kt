package com.shortfeed.app.player

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import com.shortfeed.app.data.FeedRepository
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

    // Infinite-ish scroll: fetch the next page before reaching the end.
    LaunchedEffect(pagerState.currentPage, items.size) {
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
                beyondViewportPageCount = 1, // preload adjacent video for seamless swipe
            ) { page ->
                items.getOrNull(page)?.let { item ->
                    VideoPage(item = item, active = page == pagerState.currentPage)
                }
            }
        }
    }
}

@Composable
private fun VideoPage(item: VideoItem, active: Boolean) {
    val context = LocalContext.current

    // One player per page; released when the page leaves the pager's composition range.
    val player = remember(item.id) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(item.playUrl))
            prepare()
        }
    }

    DisposableEffect(item.id) {
        onDispose { player.release() }
    }

    LaunchedEffect(active) {
        if (active) {
            player.playWhenReady = true
            player.play()
        } else {
            player.playWhenReady = false
        }
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
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM // fill screen, crop like TikTok
                    layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
