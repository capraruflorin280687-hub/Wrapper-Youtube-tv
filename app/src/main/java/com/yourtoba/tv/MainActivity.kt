package com.yourtoba.tv

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG = "YourTOBA"

        private const val TV_URL = "https://www.youtube.com/tv"

        // youtube.com/tv (Leanback) is gated to console/TV clients. These two UA
        // strings come from VacuumTube (MIT, github.com/shy1132/VacuumTube) by way
        // of the macOS app, along with its hard-won finding: an up-to-date Cobalt
        // version on every request triggers playback issues, but works fine for
        // the one-time initial handshake. So the "client" string is used for the
        // very first navigation only, and the "ongoing" one for everything after.
        private const val TV_CLIENT_UA =
            "Mozilla/5.0 (PS4; Leanback Shell) Cobalt/19.lts.0-qa; compatible; YourTOBA/0.1.0"
        private const val TV_UA =
            "Mozilla/5.0 (PS4; Leanback Shell) Cobalt/25.lts.40.1035033; compatible; YourTOBA/0.1.0"

        // Leanback reads .keyCode off any Event object. 27 is Escape, which is
        // what Leanback treats as "back".
        private const val KEYCODE_ESCAPE_DOM = 27

        // Set true to force a 3840x2160 layout viewport. Off by default: the
        // compensating scale is display-dependent and unverifiable without the
        // actual TV, and ad blocking does not depend on it. See docs/SCOPE.md.
        private const val SPOOF_4K = false

        private val ALLOWED_ORIGINS = setOf(
            "https://www.youtube.com",
            "https://m.youtube.com",
            "https://youtube.com"
        )

        private const val EMPTY_RESPONSE_MIME = "text/plain"
    }

    private lateinit var webView: WebView

    /** True until the first navigation commits, so the Cobalt handshake UA is used once. */
    private var handshakeUaActive = true

    private var blockedCount = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goFullscreen()

        webView = WebView(this)
        webView.setBackgroundColor(Color.BLACK)
        setContentView(webView)

        // Lets `chrome://inspect` attach over adb on debug builds, which is the
        // only practical way to see what Leanback is doing on the TV.
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        configureSettings(webView.settings)
        configureCookies(webView)

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.addJavascriptInterface(Bridge(), "YourTOBA")

        installAdStripper()

        webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (handshakeUaActive) {
                    // The main document request has already gone out with the
                    // handshake UA. Everything from here (subresources, innertube
                    // XHRs, media) uses the ongoing UA, which mirrors how the Mac
                    // app sets the session UA and overrides only the first load.
                    handshakeUaActive = false
                    view.settings.userAgentString = TV_UA
                    Log.i(TAG, "Handshake done, switched to ongoing Cobalt UA")
                }
                if (!documentStartSupported()) {
                    // Fallback path: inject as early as we are allowed to. This
                    // races YouTube's own scripts and may miss the first-paint
                    // ytInitialData, but it still lands before the innertube
                    // player calls that carry the video ads, which is the part
                    // that matters. Logged loudly so the real path is obvious in
                    // logcat when testing on the device.
                    view.evaluateJavascript(adStripperSource(), null)
                }
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val uri: Uri = request.url
                if (AdBlockList.shouldBlock(uri)) {
                    blockedCount++
                    if (blockedCount <= 40 || blockedCount % 50 == 0) {
                        Log.d(TAG, "blocked ($blockedCount): $uri")
                    }
                    return WebResourceResponse(
                        EMPTY_RESPONSE_MIME,
                        "utf-8",
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                return null
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                // Keep everything in this WebView, including the
                // google.com/device sign-in flow. Handing a URL to an external
                // browser on a Fire TV is a dead end for the user.
                return false
            }
        }

        webView.settings.userAgentString = TV_CLIENT_UA
        webView.loadUrl(TV_URL)
    }

    private fun configureSettings(settings: WebSettings) {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    }

    private fun configureCookies(view: WebView) {
        // Sign-in on Leanback uses the google.com/device linking flow, which
        // Google explicitly allows for TV apps. It needs cookies to persist.
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
    }

    private fun documentStartSupported(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    /**
     * Layer 2, and the one that actually kills video ads.
     *
     * addDocumentStartJavaScript is the Android equivalent of an Electron
     * preload: the script is guaranteed to run before any of the page's own
     * JavaScript, in the page's main world. That ordering guarantee is exactly
     * what the ytInitialData property guard in yourtoba.js depends on.
     */
    private fun installAdStripper() {
        val source = adStripperSource()
        if (documentStartSupported()) {
            WebViewCompat.addDocumentStartJavaScript(webView, source, ALLOWED_ORIGINS)
            Log.i(TAG, "Ad stripper installed at document start (preferred path)")
        } else {
            Log.w(
                TAG,
                "DOCUMENT_START_SCRIPT unsupported on this WebView " +
                    "(${currentWebViewVersion()}); falling back to onPageStarted injection"
            )
        }
    }

    private fun currentWebViewVersion(): String =
        WebViewCompat.getCurrentWebViewPackage(this)?.let { "${it.packageName} ${it.versionName}" }
            ?: "unknown"

    private var cachedSource: String? = null

    private fun adStripperSource(): String {
        cachedSource?.let { return it }
        val js = assets.open("yourtoba.js").bufferedReader().use { it.readText() }
        val prelude = "window.__yourtobaSpoof4K = $SPOOF_4K;\n"
        return (prelude + js).also { cachedSource = it }
    }

    /**
     * The Fire TV remote's d-pad already arrives as real Android key events that
     * the WebView translates into real DOM key events, so Leanback navigation
     * works with no help. That is why the Mac app's gamepad polling shim is gone
     * entirely. Only Back needs handling, because Android would otherwise finish
     * the activity instead of navigating within Leanback.
     *
     * Short press goes back inside Leanback. Long press exits the app. (The Fire
     * TV Home button also always works, so there is no way to get stuck.)
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            event.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            Log.i(TAG, "Long-press Back, exiting")
            finish()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.isTracking && !event.isCanceled) {
                webView.evaluateJavascript("window.__yourtobaKey($KEYCODE_ESCAPE_DOM)", null)
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun goFullscreen() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullscreen()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    /** Lets yourtoba.js confirm, in logcat, that it actually ran. */
    inner class Bridge {
        @JavascriptInterface
        fun onInjected(url: String) {
            Log.i(TAG, "Ad stripper active on $url")
        }
    }
}
