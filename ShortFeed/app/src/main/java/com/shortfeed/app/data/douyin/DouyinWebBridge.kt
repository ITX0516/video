package com.shortfeed.app.data.douyin

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.shortfeed.app.data.Platform
import com.shortfeed.app.data.QualityVariant
import com.shortfeed.app.data.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Bridges the real Douyin website into the native immersive player.
 *
 * Why: Douyin's web API rejects any non-browser caller (verified 403 "Uifid Not Found"). The only
 * way to obtain *this account's* recommendation stream is to let Douyin's own JS run. So we keep an
 * off-screen WebView on www.douyin.com (shared CookieManager → it is already logged in if the user
 * logged in via the web tab), hook fetch/XHR, and forward every `aweme_list` response to Kotlin.
 *
 * The WebView is attached behind the Compose content with alpha 0, so it renders/interacts normally
 * while the user only sees the native player. Autoplay is disabled in there: we want data, not audio.
 */
class DouyinWebBridge(private val activity: Activity) {

    private val queue = LinkedBlockingQueue<VideoItem>()
    private val seen = ConcurrentHashMap.newKeySet<String>()

    /** Feeds are switched through the page's own DOM, so stale responses must be discarded. */
    @Volatile
    private var acceptAfter = 0L

    @SuppressLint("SetJavaScriptEnabled")
    val webView: WebView = WebView(activity).apply {
        setBackgroundColor(Color.BLACK)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = DESKTOP_UA // same session/layout as the login page
            useWideViewPort = true
            loadWithOverviewMode = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true // data only, no audio
        }
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(HOOK_JS, null)
            }
        }
        addJavascriptInterface(FeedSink(), "ShortFeedBridge")
        loadUrl(START_URL)
    }

    private inner class FeedSink {
        @JavascriptInterface
        fun onFeed(json: String) = ingest(json)
    }

    /** Must be called on the UI thread, after setContent(), so the Compose view is above it. */
    fun attachBehind() {
        if (webView.parent != null) return
        webView.alpha = 0f
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        content.addView(
            webView,
            0,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    /** Blocks up to [timeoutMs] for the first item, then drains whatever else is ready. */
    suspend fun takeBatch(timeoutMs: Long, max: Int): List<VideoItem> =
        withContext(Dispatchers.IO) {
            val first = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
                ?: return@withContext emptyList()
            val out = ArrayList<VideoItem>(max)
            out += first
            queue.drainTo(out, max - 1)
            out
        }

    private fun ingest(json: String) {
        // Drop responses that were already in flight when the feed was switched.
        if (System.currentTimeMillis() < acceptAfter) return
        val arr = runCatching { JSONObject(json).optJSONArray("aweme_list") }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            val id = a.optString("aweme_id")
            if (id.isEmpty() || !seen.add(id)) continue
            val video = a.optJSONObject("video") ?: continue
            // 精选 cards often ship an empty play_addr until the item is "activated"; fall back to
            // the rendition list, then to the vid-based play endpoint (needs the web cookies).
            val play = fullUrl(
                video.optJSONObject("play_addr")?.optJSONArray("url_list")?.optString(0).orEmpty()
            ).ifEmpty { firstBitRateUrl(video) }
                .ifEmpty { vidPlayUrl(video.optString("vid")) }
            if (play.isEmpty()) continue // image posts have no video at all — skip
            val cover = fullUrl(
                video.optJSONObject("cover")?.optJSONArray("url_list")?.optString(0).orEmpty()
            )
            queue.offer(
                VideoItem(
                    id = id,
                    platform = Platform.DOUYIN,
                    title = a.optString("desc"),
                    author = a.optJSONObject("author")?.optString("nickname").orEmpty(),
                    cover = cover,
                    playUrl = play,
                    sourceKey = KEY,
                    variants = parseVariants(video),
                )
            )
        }
    }

    /**
     * The web payload ships several renditions under video.bit_rate. We keep the ones that expose a
     * direct URL, label them by their short side (540P / 720P / 1080P …) and sort highest first.
     * Anything unexpected is skipped rather than guessed, so the default play_addr always remains.
     */
    private fun parseVariants(video: JSONObject): List<QualityVariant> {
        val arr = video.optJSONArray("bit_rate") ?: return emptyList()
        val byLabel = LinkedHashMap<String, String>()
        for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i) ?: continue
            val addr = entry.optJSONObject("play_addr") ?: continue
            val url = fullUrl(addr.optJSONArray("url_list")?.optString(0).orEmpty())
            if (url.isEmpty()) continue
            val w = addr.optInt("width", 0).takeIf { it in 1..4096 }
                ?: entry.optInt("width", 0).takeIf { it in 1..4096 } ?: 0
            val h = addr.optInt("height", 0).takeIf { it in 1..4096 }
                ?: entry.optInt("height", 0).takeIf { it in 1..4096 } ?: 0
            val label = if (w > 0 && h > 0) {
                "${standardHeight(minOf(w, h))}P"
            } else {
                entry.optString("gear_name").takeIf { it.isNotEmpty() } ?: continue
            }
            byLabel.putIfAbsent(label, url)
        }
        return byLabel
            .map { QualityVariant(it.key, it.value) }
            .sortedByDescending { it.label.removeSuffix("P").toIntOrNull() ?: 0 }
    }

    private fun firstBitRateUrl(video: JSONObject): String {
        val arr = video.optJSONArray("bit_rate") ?: return ""
        for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i) ?: continue
            val url = fullUrl(
                entry.optJSONObject("play_addr")?.optJSONArray("url_list")?.optString(0).orEmpty()
            )
            if (url.isNotEmpty()) return url
        }
        return ""
    }

    /** Fallback used when the payload withholds every address: the vid-based web play endpoint. */
    private fun vidPlayUrl(vid: String): String =
        if (vid.isEmpty()) "" else
            "https://www.douyin.com/aweme/v1/play/?video_id=$vid&ratio=1080p&line=0"

    private fun standardHeight(px: Int): Int =
        STANDARD_HEIGHTS.minByOrNull { kotlin.math.abs(it - px) } ?: px

    /** Clears buffered items. Call when the page switches feed (推荐 ⇄ 关注). */
    fun resetBuffer() {
        queue.clear()
        seen.clear()
    }

    /** Outcome of asking the page to switch feeds. */
    data class TabSwitchResult(val clicked: Boolean, val activeTab: String?)

    /**
     * Clicks the feed tab whose visible text is exactly [label] (精选 / 推荐 / 关注).
     *
     * The site is a virtualised SPA, so the click is best-effort DOM work. Afterwards we read back
     * which tab the page itself marks as active: if it disagrees with what was requested, the
     * caller must tell the user rather than silently presenting the old feed as the new one.
     */
    suspend fun switchTab(label: String): TabSwitchResult = withContext(Dispatchers.Main) {
        val clicked = evaluate(clickTabJs(label)).orEmpty().trim('"') == "ok"
        if (!clicked) return@withContext TabSwitchResult(false, null)

        delay(1_000) // let the SPA route and render
        val active = evaluate(activeTabJs()).orEmpty().trim('"').ifEmpty { null }
        resetBuffer()
        acceptAfter = System.currentTimeMillis() + 400
        TabSwitchResult(true, active)
    }

    private suspend fun evaluate(js: String): String? = suspendCancellableCoroutine { cont ->
        webView.evaluateJavascript(js) { result -> if (cont.isActive) cont.resume(result) }
    }

    /**
     * Best-effort real like. The page is a virtualised list, so the item we are playing may already
     * be unmounted — in that case nothing is clicked (the caller keeps the local like).
     */
    fun likeAweme(awemeId: String, onResult: (Boolean) -> Unit) {
        activity.runOnUiThread {
            webView.evaluateJavascript(likeJs(awemeId)) { r ->
                onResult(r?.trim()?.trim('"') == "ok")
            }
        }
    }

    fun onResume() = webView.onResume()

    fun onPause() = webView.onPause()

    fun destroy() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    private fun fullUrl(url: String): String = when {
        url.isEmpty() -> ""
        url.startsWith("//") -> "https:$url"
        else -> url
    }

    /** Clicks the tab whose visible text is exactly [label]; returns 'ok' or 'nf'. */
    private fun clickTabJs(label: String): String = """
        (function(){
          var want = '$label';
          var norm = function(s){ return (s||'').replace(/\s+/g,''); };
          var all = document.querySelectorAll('span,div,a,li,button');
          for (var i = 0; i < all.length; i++) {
            var el = all[i];
            if (el.children.length > 0) continue;
            if (norm(el.innerText) !== want) continue;
            if (!el.offsetParent) continue;
            var t = el.closest('[role="tab"],a,button,li') || el;
            try { t.click(); return 'ok'; } catch(e) { return 'nf'; }
          }
          return 'nf';
        })();
    """

    /** Reads back which of the three feed tabs the page currently marks as active ('' if unknown). */
    private fun activeTabJs(): String = """
        (function(){
          var norm = function(s){ return (s||'').replace(/\s+/g,''); };
          var names = ['精选','推荐','关注'];
          var sel = document.querySelectorAll('[aria-selected="true"],[class*="active"],[class*="selected"]');
          for (var i = 0; i < sel.length; i++) {
            var t = norm(sel[i].innerText);
            for (var j = 0; j < names.length; j++) if (t === names[j]) return names[j];
          }
          return '';
        })();
    """

    /** Finds the DOM node of [awemeId] (if still mounted) and clicks its like button. */
    private fun likeJs(awemeId: String): String = """
        (function(){
          var id = '$awemeId';
          var nodes = document.querySelectorAll('a[href*="/video/' + id + '"]');
          var root = null;
          for (var i = 0; i < nodes.length; i++) {
            if (nodes[i].offsetParent) { root = nodes[i]; break; }
          }
          if (!root) return 'no-item';
          var r = root;
          while (r && r !== document.body) {
            var b = r.querySelector('[data-e2e="video-player-digg"],[data-e2e*="digg"],[aria-label*="点赞"]');
            if (b) {
              if ((b.getAttribute('aria-pressed') || '') === 'true') return 'already';
              b.click();
              return 'ok';
            }
            r = r.parentElement;
          }
          return 'no-btn';
        })();
    """

    companion object {
        const val KEY = "douyin"
        private const val START_URL = "https://www.douyin.com/"
        private val STANDARD_HEIGHTS = intArrayOf(240, 360, 480, 540, 720, 1080, 1440, 2160)
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        /**
         * Hooks the page's own network layer (so signatures are still produced by Douyin's JS) and
         * nudges the feed to keep loading. Guarded so repeated onPageFinished calls are harmless.
         */
        private const val HOOK_JS = """
            (function(){
              if (window.__sf_hooked) return;
              window.__sf_hooked = true;
              function post(t){
                try {
                  if (t && t.indexOf('aweme_list') >= 0) ShortFeedBridge.onFeed(t);
                } catch(e) {}
              }
              try {
                var of = window.fetch;
                if (of) {
                  window.fetch = function(){
                    var p = of.apply(this, arguments);
                    try {
                      p.then(function(r){
                        try {
                          if (r && r.url && r.url.indexOf('/aweme/') >= 0) {
                            r.clone().text().then(post).catch(function(){});
                          }
                        } catch(e) {}
                      }).catch(function(){});
                    } catch(e) {}
                    return p;
                  };
                }
              } catch(e) {}
              try {
                var OX = window.XMLHttpRequest;
                var NX = function(){
                  var x = new OX();
                  var u = '';
                  var oo = x.open;
                  x.open = function(m, url){ u = String(url || ''); return oo.apply(x, arguments); };
                  x.addEventListener('load', function(){
                    try { if (u.indexOf('/aweme/') >= 0) post(x.responseText); } catch(e) {}
                  });
                  return x;
                };
                NX.prototype = OX.prototype;
                window.XMLHttpRequest = NX;
              } catch(e) {}
              setInterval(function(){
                try {
                  var el = document.scrollingElement || document.documentElement;
                  if (!el) return;
                  el.scrollTop = el.scrollTop + Math.max(800, (window.innerHeight || 800) * 0.9);
                  window.dispatchEvent(new Event('scroll'));
                } catch(e) {}
              }, 2200);
            })();
        """
    }
}
