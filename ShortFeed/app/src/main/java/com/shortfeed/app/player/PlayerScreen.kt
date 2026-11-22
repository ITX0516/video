package com.shortfeed.app.player

import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.shortfeed.app.Prefs
import com.shortfeed.app.data.FeedRepository
import com.shortfeed.app.data.Platform
import com.shortfeed.app.data.VideoItem

/** How the video frame maps onto the screen. Persisted across launches. */
enum class ScaleMode(val label: String, val resizeMode: Int) {
    FIT("适应", AspectRatioFrameLayout.RESIZE_MODE_FIT),      // whole frame, letterboxed
    STRETCH("拉伸", AspectRatioFrameLayout.RESIZE_MODE_FILL), // distort to fill
    ZOOM("填充", AspectRatioFrameLayout.RESIZE_MODE_ZOOM);    // fill, crop the overflow

    companion object {
        fun fromKey(key: String?): ScaleMode = entries.firstOrNull { it.name == key } ?: FIT
    }
}

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 3f
private const val ZOOM_STEP = 0.25f

/**
 * The entire in-app surface: a full-screen vertical pager of videos.
 * A small translucent panel (top-right) picks the picture mode / zoom and can be dismissed with
 * 「隐藏全部 UI」; after that the screen is 100% video and a tap brings it back. Two-finger pinch
 * also zooms, without stealing the single-finger vertical swipe.
 */
@Composable
fun PlayerScreen(repository: FeedRepository) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context.applicationContext) }
    val items by repository.items.collectAsStateWithLifecycle()
    val activeKey by repository.activeKey.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(pageCount = { items.size })

    var scaleMode by remember { mutableStateOf(ScaleMode.fromKey(prefs.loadScaleMode())) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var controlsVisible by remember { mutableStateOf(true) }

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
                    VideoPage(
                        item = item,
                        active = page == pagerState.currentPage,
                        repository = repository,
                        scaleMode = scaleMode,
                        zoom = zoom,
                    )
                }
            }
        }

        // Gesture layer. Pinch only reacts to 2+ pointers so the vertical swipe is untouched;
        // tapping brings the controls back after they were hidden.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } >= 2) {
                                val change = event.calculateZoom()
                                if (change.isFinite() && change != 1f) {
                                    zoom = (zoom * change).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { controlsVisible = true }
                }
        )

        if (controlsVisible) {
            ControlsPanel(
                scaleMode = scaleMode,
                zoom = zoom,
                onScaleMode = {
                    scaleMode = it
                    prefs.saveScaleMode(it.name)
                },
                onZoom = { zoom = it.coerceIn(MIN_ZOOM, MAX_ZOOM) },
                onHide = { controlsVisible = false },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
            )
        }
    }
}

@Composable
private fun ControlsPanel(
    scaleMode: ScaleMode,
    zoom: Float,
    onScaleMode: (ScaleMode) -> Unit,
    onZoom: (Float) -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = Color.Black.copy(alpha = 0.55f),
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScaleMode.entries.forEach { mode ->
                    TextButton(onClick = { onScaleMode(mode) }) {
                        Text(
                            text = mode.label,
                            color = if (mode == scaleMode) Color(0xFFD0BCFF) else Color.White,
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onZoom(zoom - ZOOM_STEP) }) {
                    Text("－", color = Color.White)
                }
                Text(
                    text = "${(zoom * 100).toInt()}%",
                    color = Color.White,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { onZoom(zoom + ZOOM_STEP) }) {
                    Text("＋", color = Color.White)
                }
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = onHide) {
                    Text("隐藏全部 UI", color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun VideoPage(
    item: VideoItem,
    active: Boolean,
    repository: FeedRepository,
    scaleMode: ScaleMode,
    zoom: Float,
) {
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
                    resizeMode = scaleMode.resizeMode
                    layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                    this.player = player
                }
            },
            update = { view -> view.resizeMode = scaleMode.resizeMode },
            modifier = Modifier
                .fillMaxSize()
                // Zoom is a pure presentation scale on top of the resize mode; clip so a zoomed
                // frame never bleeds into the neighbouring pager page.
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    clip = true
                },
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
