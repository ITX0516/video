package com.shortfeed.app.player

import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.shortfeed.app.Prefs
import com.shortfeed.app.data.FeedRepository
import com.shortfeed.app.data.Platform
import com.shortfeed.app.data.VideoItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

/** Bilibili qn codes. 1080P needs a logged-in account to actually be granted. */
private val QUALITY_OPTIONS = listOf(
    "360P" to 16,
    "480P" to 32,
    "720P" to 64,
    "1080P" to 80,
)

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
    initialQuality: Int = 64,
    onQualityChange: (Int) -> Unit = {},
    onToggleLike: (VideoItem, String) -> Boolean = { _, _ -> false },
    onToggleFavorite: (VideoItem, String) -> Boolean = { _, _ -> false },
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
    val isBilibili = activeKey == "bilibili"
    val currentItem = items.getOrNull(pagerState.currentPage)
    val latestItems by rememberUpdatedState(items)

    // Last resolved play URL per item id, so that like/favourite can persist something replayable.
    val playUrls = remember { mutableStateMapOf<String, String>() }
    var quality by remember { mutableStateOf(initialQuality) }
    var qualityVersion by remember { mutableStateOf(0) }
    // Douyin rendition label ("1080P"…); empty = the feed's own default address.
    var dyQuality by remember { mutableStateOf(prefs.loadDouyinQuality()) }
    var showInfo by remember { mutableStateOf(false) }
    fun applyQuality(qn: Int) {
        if (qn == quality) return
        quality = qn
        qualityVersion++
        onQualityChange(qn)
    }
    fun applyDouyinQuality(label: String) {
        if (label == dyQuality) return
        dyQuality = label
        prefs.saveDouyinQuality(label)
        qualityVersion++
    }

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
        repository.prefetch(pagerState.currentPage, lookahead = 3)
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
                // Keep neighbouring pages composed so their players are already prepared.
                beyondViewportPageCount = 1,
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
            ) { page ->
                latestItems.getOrNull(page)?.let { item ->
                    VideoPage(
                        item = item,
                        active = page == pagerState.currentPage,
                        repository = repository,
                        scaleMode = scaleMode,
                        transform = transform,
                        qualityVersion = qualityVersion,
                        dyQuality = dyQuality,
                        showInfo = showInfo && controlsVisible,
                        onResolved = { playUrls[item.id] = it },
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
                    currentItem?.let {
                        val url = playUrls[it.id] ?: it.playUrl
                        showFlash(if (onToggleLike(it, url)) "♥" else "♡")
                    }
                },
                onFavorite = {
                    currentItem?.let {
                        val url = playUrls[it.id] ?: it.playUrl
                        showFlash(if (onToggleFavorite(it, url)) "★ 收藏" else "☆ 取消")
                    }
                },
                showQuality = isBilibili,
                quality = quality,
                onQuality = { applyQuality(it) },
                infoOn = showInfo,
                onInfoToggle = { showInfo = !showInfo },
                douyinOptions = currentItem?.variants?.map { it.label }.orEmpty(),
                douyinQuality = dyQuality,
                onDouyinQuality = { applyDouyinQuality(it) },
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
    showQuality: Boolean,
    quality: Int,
    onQuality: (Int) -> Unit,
    infoOn: Boolean,
    onInfoToggle: () -> Unit,
    douyinOptions: List<String>,
    douyinQuality: String,
    onDouyinQuality: (String) -> Unit,
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
                TextButton(onClick = onInfoToggle) {
                    Text(if (infoOn) "隐藏信息" else "信息", color = Color.White)
                }
                if (tabSwitch != null) {
                    TextButton(onClick = tabSwitch) {
                        Text("切到$tabTarget", color = Color.White)
                    }
                }
            }
            if (showQuality) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "清晰度",
                        color = Color.White,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    QUALITY_OPTIONS.forEach { (label, qn) ->
                        TextButton(onClick = { onQuality(qn) }) {
                            Text(
                                text = label,
                                color = if (qn == quality) Color(0xFFD0BCFF) else Color.White,
                            )
                        }
                    }
                }
            }
            if (douyinOptions.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = "清晰度",
                        color = Color.White,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    val qualityChoices = listOf("自动" to "", "最低" to "最低") +
                        douyinOptions.map { it to it }
                    qualityChoices.forEach { (label, value) ->
                        TextButton(onClick = { onDouyinQuality(value) }) {
                            Text(
                                text = label,
                                color = if (value == douyinQuality) Color(0xFFD0BCFF) else Color.White,
                            )
                        }
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
    qualityVersion: Int,
    dyQuality: String,
    showInfo: Boolean,
    onResolved: (String) -> Unit,
) {
    val context = LocalContext.current
    // Douyin ships several renditions: honour the chosen one, and if this video lacks it,
    // fall back to the nearest lower rendition (or the lowest) instead of stalling.
    val preferredUrl = remember(item.id, dyQuality) { pickVideoUrl(item, dyQuality) }
    var playUrl by remember(item.id, qualityVersion, dyQuality) { mutableStateOf(preferredUrl) }

    // One player per page, with the request headers each platform's CDN demands.
    // Buffer targets are tuned for quick starts while swiping, not for long-form playback.
    val player = remember(item.id) {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(DESKTOP_UA) // mobile & ExoPlayer default UAs get 403 from these CDNs
            .setDefaultRequestProperties(refererFor(item.platform))
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 8_000,
                /* maxBufferMs = */ 20_000,
                /* bufferForPlaybackMs = */ 800,
                /* bufferForPlaybackAfterRebufferMs = */ 1_500,
            )
            .build()
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .build()
    }

    // Per-page load state, so a black screen always explains itself.
    var loadState by remember(item.id) { mutableStateOf(LoadState.Loading) }
    var errorText by remember(item.id) { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    DisposableEffect(item.id) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                loadState = LoadState.Ready
            }

            override fun onPlayerError(error: PlaybackException) {
                errorText = error.errorCodeName
                loadState = LoadState.Error
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    fun retry() {
        loadState = LoadState.Loading
        errorText = ""
        scope.launch {
            // The URL may simply have expired: drop the cache and resolve again.
            repository.clearResolvedUrls()
            val url = repository.resolvePlayUrl(item)
            if (url.isNotEmpty()) {
                if (url == playUrl) {
                    // Same address: force a fresh prepare, the effect won't re-run on an equal value.
                    player.prepare()
                    player.play()
                } else {
                    playUrl = url
                }
                onResolved(url)
            } else if (playUrl.isNotEmpty()) {
                player.prepare()
                player.play()
            } else {
                errorText = "无法获取播放地址"
                loadState = LoadState.Error
            }
        }
    }

    // Resolve the direct URL as soon as the page is composed (also for the preloaded neighbours),
    // so swiping to an already-composed page never waits on resolution.
    LaunchedEffect(item.id, qualityVersion) {
        if (playUrl.isEmpty()) {
            val url = repository.resolvePlayUrl(item)
            if (url.isNotEmpty()) {
                playUrl = url
                onResolved(url)
            } else if (item.playUrl.isEmpty()) {
                errorText = "无法获取播放地址"
                loadState = LoadState.Error
            }
        }
    }

    // Attach media once a URL is known, and (re)start/pause with page activity.
    LaunchedEffect(playUrl, active) {
        if (playUrl.isEmpty()) return@LaunchedEffect
        if (player.currentMediaItem == null ||
            player.currentMediaItem?.localConfiguration?.uri?.toString() != playUrl
        ) {
            loadState = LoadState.Loading
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

        // Loading / failure feedback: a black screen must never be silent.
        if (active && loadState != LoadState.Ready) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (loadState == LoadState.Loading) {
                    CircularProgressIndicator(color = Color.White)
                    Text(
                        text = "加载中…",
                        color = Color.White,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                } else {
                    Text(
                        text = "视频加载失败",
                        color = Color.White,
                        style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = errorText,
                        color = Color.White.copy(alpha = 0.7f),
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    TextButton(onClick = { retry() }) {
                        Text("重试", color = Color(0xFFD0BCFF))
                    }
                    Text(
                        text = "也可以直接滑到下一条",
                        color = Color.White.copy(alpha = 0.5f),
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // Optional video info overlay (toggled from the control panel).
        if (active && showInfo) {
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                shape = RoundedCornerShape(8.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = item.title.ifBlank { "（无标题）" },
                        color = Color.White,
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "${item.author.ifBlank { "未知作者" }} · ${item.platform.name}",
                        color = Color.White.copy(alpha = 0.8f),
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        text = "id: ${item.id}",
                        color = Color.White.copy(alpha = 0.5f),
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

private enum class LoadState { Loading, Ready, Error }

/** Numeric part of a rendition label ("720P" -> 720), used for nearest-match fallback. */
private fun qualityNumber(label: String): Int = label.filter { it.isDigit() }.toIntOrNull() ?: 0

/**
 * Pick the direct URL for a video under the current Douyin quality preference.
 * "最低" always takes the lowest available rendition; a preferred rendition the video does not
 * offer degrades to the nearest lower one (or the lowest), never to an empty/black page.
 */
private fun pickVideoUrl(item: VideoItem, dyQuality: String): String {
    val variants = item.variants.filter { it.url.isNotEmpty() }
    if (variants.isEmpty()) return item.playUrl
    val sorted = variants.sortedBy { qualityNumber(it.label) }
    if (dyQuality == "最低") return sorted.first().url
    variants.firstOrNull { it.label == dyQuality }?.let { return it.url }
    if (dyQuality.isEmpty() || dyQuality == "自动") {
        return item.playUrl.ifEmpty { sorted.last().url }
    }
    val target = qualityNumber(dyQuality)
    val lowerOrEqual = sorted.filter { qualityNumber(it.label) <= target }.lastOrNull()
    return (lowerOrEqual ?: sorted.first()).url
}

private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

private fun refererFor(platform: Platform): Map<String, String> = when (platform) {
    Platform.BILIBILI -> mapOf("Referer" to "https://www.bilibili.com")
    Platform.DOUYIN -> mapOf("Referer" to "https://www.douyin.com")
    Platform.KUAISHOU -> mapOf("Referer" to "https://www.kuaishou.com")
}
