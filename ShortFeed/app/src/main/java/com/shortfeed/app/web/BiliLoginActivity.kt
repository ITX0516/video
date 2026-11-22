package com.shortfeed.app.web

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shortfeed.app.Prefs

/**
 * Bilibili login, used only to obtain the SESSDATA cookie so the native player can read *this
 * account's* personalised recommendation feed instead of the anonymous one.
 *
 * Deliberately minimal: a desktop-UA WebView on Bilibili's own login page. When SESSDATA shows up
 * in the cookie jar we persist it and finish. Nothing is sent anywhere; the cookie stays on-device.
 */
class BiliLoginActivity : ComponentActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                userAgentString = DESKTOP_UA
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    captureSession()
                }
            }
            loadUrl(LOGIN_URL)
        }

        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
        )

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!captureSession() && webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    /** Persists SESSDATA if present. Returns true when a session was found. */
    private fun captureSession(): Boolean {
        val cookie = CookieManager.getInstance()
            .getCookie("https://www.bilibili.com")
            .orEmpty()
        val sess = cookie.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("SESSDATA=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotEmpty() && it != "deleted" }
            ?: return false
        Prefs(this).saveBiliSession(sess)
        Toast.makeText(this, "Bilibili 登录成功，将使用个性化推荐", Toast.LENGTH_SHORT).show()
        finish()
        return true
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        const val LOGIN_URL = "https://passport.bilibili.com/login"
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
