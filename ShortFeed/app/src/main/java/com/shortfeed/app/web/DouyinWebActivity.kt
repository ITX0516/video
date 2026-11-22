package com.shortfeed.app.web

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Douyin "web" tab: the real Douyin website inside a WebView.
 *
 * Why this instead of a native client: as of 2026-11 the web API rejects every non-browser caller
 * (verified: 403 "Blocked by ArgusSecurityPlugin Uifid Not Found", with or without a_bogus; the
 * homepage only ships an obfuscated JS shell). Running the site's own JS in a real browser engine
 * sidesteps all of that — login, like and favourite work natively because they ARE the official
 * web UI. No signing is reverse-engineered, so nothing here breaks when Douyin rotates a signature.
 *
 * Fixes applied for a usable, full-screen experience:
 *  - plain mobile Chrome UA (a "wv" UA and the default WebView UA get sniffed/degraded)
 *  - autoplay without a tap (mediaPlaybackRequiresUserGesture = false)
 *  - immersive system bars + HTML5 video fullscreen via WebChromeClient
 *  - a small CSS injection that hides the "open the app" download nudges
 */
class DouyinWebActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var root: FrameLayout
    private lateinit var chromeClient: WebChromeClient
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false // autoplay, like the native player
                userAgentString = DESKTOP_UA
                useWideViewPort = true
                loadWithOverviewMode = true
                // Desktop pages are wide; pinch-zoom is essential. Hide the ugly +/- widget but
                // keep the gesture. This also scales inline video.
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(false)
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val uri = request.url
                    // Keep http(s) navigation inside; hand app schemes (snssdk://, bytedance://…)
                    // to the system so "open in Douyin" still does something sensible.
                    return if (uri.scheme == "http" || uri.scheme == "https") {
                        false
                    } else {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                        true
                    }
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    view.evaluateJavascript(HIDE_APP_NUDGE_JS, null)
                }
            }

            chromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    if (customView != null) {
                        callback.onCustomViewHidden()
                        return
                    }
                    customView = view
                    customViewCallback = callback
                    root.addView(
                        view,
                        FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT),
                    )
                    this@DouyinWebActivity.webView.visibility = View.GONE
                    hideSystemBars()
                }

                override fun onHideCustomView() {
                    customView?.let { root.removeView(it) }
                    customView = null
                    customViewCallback?.onCustomViewHidden()
                    customViewCallback = null
                    this@DouyinWebActivity.webView.visibility = View.VISIBLE
                }
            }
            webChromeClient = chromeClient
        }

        root.addView(webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> chromeClient.onHideCustomView()
                    webView.canGoBack() -> webView.goBack()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        webView.loadUrl(START_URL)
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        const val START_URL = "https://www.douyin.com/"

        // Desktop Chrome UA. The mobile UA gets the "open in app" login wall that cannot complete
        // in a WebView; the desktop site logs in normally (QR / phone + code).
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        // Hide only obvious "download / open app" nudges; conservative so the real UI keeps working.
        const val HIDE_APP_NUDGE_JS = """
            (function(){
              var css='[class*="open-app"],[class*="openApp"],[class*="download-btn"],'+
                      '[class*="download-app"],[class*="guide-download"],[data-e2e*="download"]'+
                      '{display:none!important}';
              var s=document.createElement('style');s.type='text/css';
              s.appendChild(document.createTextNode(css));
              (document.head||document.documentElement).appendChild(s);
            })();
        """
    }
}
