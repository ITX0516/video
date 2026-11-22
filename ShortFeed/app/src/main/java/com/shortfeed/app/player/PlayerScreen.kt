package com.shortfeed.app.player

import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.BackHandler
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
import kotlinx.coroutines.delay

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

/** Pinch state: scale plus pan offset in pixels. Shared by every video (carries over on swipe). */
private data class FrameTransform(
    val scale: Float = 1f,
    val offset: Offset = Offset.Zero,
)

/**
 * The entire in-app surface: a full-screen vertical pager of videos.
 * A small translucent panel (top-right) picks the picture mode; 「隐藏全部 UI」 keeps the screen
 * fully video from then on, and the back key brings the panel back. Pinch with two fingers to
 * zoom and drag with two fingers to pan — single-finger paging is never intercepted.
 */
@Composable
fun PlayerScreen(
    repository: FeedRepository,
    onToggleLike: (VideoItem) -> Boolean = { false },
    onToggleFavorite: (VideoItem) -> Boolean = { false },
    onSwitchDouyinTab: (Boolean) -> String? = { null },
) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context.applicationContext) }
    val items by repository.items.collectAsStateWithLifecycle()
    val activeKey by repository.activeKey.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(pageCount = { items.size })

    var scaleMode by remember { mutableStateOf(ScaleMode.fromKey(prefs.loadScaleMode())) }
    var controlsVisible by remember { mutableStateOf(true) }
    var douyinFollowing by remember { mutableStateOf(false) }
    // One transform for all videos: the zoom/pan you set carries over when you swipe to the next one.
    var transform by remember { mutableStateOf(FrameTransform()) }
    val isDouyin = activeKey == "douyin"
    val currentItem = items.getOrNull(pagerState.currentPage)
    val latestItems by rememberUpdatedState(items)

    // Hiding the UI is sticky across videos. Back brings the controls back; when they are already
    // visible the system default (exit the player) applies.
    BackHandler(enabled = !controlsVisible) { controlsVisible = true }

    // Transient feedback (♥ / ★ / tab name) — the only "UI" allowed in immersive mode.
    var flash by remember { mutableStateOf<String?>(null) }
    var flashSeq by remember { mutableStateOf(0) }
    LaunchedEffect(flashSeq) {
        if (flashSeq > 0) {
            delay(900)
            flash = null
        }
    }
    fun showFlash(text: String) {
        flash = text
        flashSeq++
    }

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
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        // Two fingers only: pinch to scale, drag to pan. Single-finger events are
                        // never touched, so vertical paging stays exactly as before.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } >= 2) {
                                    val cur = transform
                                    val dZoom = event.calculateZoom()
                                    val dPan = event.calculatePan()
                                    val newScale = (if (dZoom.isFinite()) cur.scale * dZoom else cur.scale)
                                        .coerceIn(MIN_ZOOM, MAX_ZOOM)
                                    val maxX = size.width * (newScale - 1f) / 2f
                                    val maxY = size.height * (newScale - 1f) / 2f
                                    val nx = if (newScale <= 1f) 0f else cur.offset.x + dPan.x
                                    val ny = if (newScale <= 1f) 0f else cur.offset.y + dPan.y
                                    transform = FrameTransform(
                                        scale = newScale,
                                        offset = Offset(
                                            nx.coerceIn(-maxX, maxX),
                                            ny.coerceIn(-maxY, maxY),
                                        ),
                                    )
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    },
                beyondViewportPageCount = 1,
            ) { page ->
                latestItems.getOrNull(page)?.let { item ->
                    VideoPage(
                        item = item,
                        active = page == pagerState.currentPage,
                        repository = repository,
                        scaleMode = scaleMode,
                        transform = transform,
                    )
                }
            }
        }

        if (controlsVisible) {
            ControlsPanel(
                scaleMode = scaleMode,
                onScaleMode = {
                    scaleMode = it
                    prefs.saveScaleMode(it.name)
                },
                scalePercent = (transform.scale * 100).toInt(),
                onResetZoom = { transform = FrameTransform() },
                onHide = { controlsVisible = false },
                onLike = {
                    currentItem?.let { showFlash(if (onToggleLike(it)) "♥" else "♡") }
                },
                onFavorite = {
                    currentItem?.let { showFlash(if (onToggleFavorite(it)) "★ 收藏" else "☆ 取消") }
                },
                tabSwitch = if (isDouyin) {
                    {
                        onSwitchDouyinTab(!douyinFollowing)?.let { label ->
                            douyinFollowing = !douyinFollowing
                            showFlash("切换到「$label」")
                        }
                    }
                } else {
                    null
                },
                tabTarget = if (douyinFollowing) "推荐" else "关注",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
            )
        }

        // Transient feedback only — not interactive, does not block taps.
        flash?.let { text ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = Color.Black.copy(alpha = 0.45f),
                    contentColor = Color.White,
                ) {
                    Text(
                        text = text,
                        fontSize = if (text.length <= 2) 64.sp else 22.sp,
                        color = if (text.startsWith("♥")) Color(0xFFFF4D6A) else Color.White,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlsPanel(
    scaleMode: ScaleMode,
    scalePercent: Int,
    onScaleMode: (ScaleMode) -> Unit,
    onResetZoom: () -> Unit,
    onHide: () -> Unit,
    onLike: () -> Unit,
    onFavorite: () -> Unit,
    tabSwitch: (() -> Unit)?,
    tabTarget: String,
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
                Text(
                    text = "双指缩放 $scalePercent%",
                    color = Color.White,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                if (scalePercent > 100) {
                    TextButton(onClick = onResetZoom) {
                        Text("复位", color = Color.White)
                    }
                }
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = onHide) {
                    Text("隐藏全部 UI", color = Color.White)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onLike) {
                    Text("♥ 赞", color = Color.White)
                }
                TextButton(onClick = onFavorite) {
                    Text("★ 收藏", color = Color.White)
                }
                if (tabSwitch != null) {
                    TextButton(onClick = tabSwitch) {
                        Text("切到$tabTarget", color = Color.White)
                    }
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
    transform: FrameTransform,
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
                // Pinch scale + pan are a pure presentation transform; clip so a zoomed frame never
                // bleeds into the neighbouring pager page.
                .graphicsLayer {
                    scaleX = transform.scale
                    scaleY = transform.scale
                    translationX = transform.offset.x
                    translationY = transform.offset.y
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
