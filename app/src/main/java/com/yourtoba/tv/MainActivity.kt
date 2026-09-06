package com.yourtoba.tv

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
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

        // Origin allowlist for the document-start script. The wildcard covers
        // whichever youtube.com subdomain Leanback lands on or redirects to;
        // without it a redirect to an unlisted host would silently run the page
        // with no ad stripping at all.
        private val ALLOWED_ORIGINS = setOf(
            "https://*.youtube.com",
            "https://youtube.com"
        )

        private const val EMPTY_RESPONSE_MIME = "text/plain"

        private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT

        /** How long Leanback gets to put something on screen before we explain. */
        private const val BOOT_CHECK_DELAY_MS = 15_000L

        /**
         * Which video codecs the page is allowed to claim it can play.
         *
         * A first-generation Fire TV Stick 4K has no AV1 hardware decoder, but
         * Chromium's software AV1 decoder makes the WebView answer "supported"
         * anyway, so YouTube serves AV1 and the stick chokes: audio with a black
         * screen, or heavy stutter. "no-av1" leaves VP9 and H.264, both of which
         * that device decodes in hardware.
         *
         *   "no-av1"    block AV1 only (default)
         *   "h264-only" also block VP9, if VP9 turns out to be unreliable too
         *   "all"       no filtering
         */
        private const val CODEC_POLICY = "no-av1"

        private const val STATS_POLL_MS = 1000L
    }

    private lateinit var webView: WebView
    private lateinit var diagnostic: TextView
    private lateinit var stats: TextView
    private var statsVisible = false
    private val handler = Handler(Looper.getMainLooper())
    private val statsHandler = Handler(Looper.getMainLooper())

    /** True until the first navigation commits, so the Cobalt handshake UA is used once. */
    private var handshakeUaActive = true

    private var blockedCount = 0

    /** False if the preferred path was unavailable or refused at install time. */
    private var documentStartInstalled = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goFullscreen()

        webView = WebView(this)
        webView.setBackgroundColor(Color.BLACK)

        // Leanback failing to boot otherwise looks like a black screen with no
        // explanation, and the cause is almost always the device's WebView being
        // too old rather than anything in this app. Say so on screen, since the
        // person looking at it is across the room with a remote, not at a laptop
        // reading logcat.
        diagnostic = TextView(this).apply {
            setBackgroundColor(Color.BLACK)
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(96, 96, 96, 96)
            visibility = View.GONE
        }

        // Toggled with the remote's Menu button. The person debugging playback
        // is on a sofa, not at a terminal reading logcat.
        stats = TextView(this).apply {
            setBackgroundColor(0xB0000000.toInt())
            setTextColor(0xFF9DFF9D.toInt())
            textSize = 13f
            setPadding(28, 24, 28, 24)
            visibility = View.GONE
        }

        setContentView(FrameLayout(this).apply {
            addView(webView, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(diagnostic, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(stats, FrameLayout.LayoutParams(WRAP, WRAP).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(36, 36, 36, 36)
            })
        })

        // On unconditionally, not just in debug builds. This is a personal
        // sideload app on a device where ADB is already enabled (you cannot
        // install it otherwise), so this exposes nothing new, and `chrome://
        // inspect` is the only practical way to see what Leanback is actually
        // doing on a TV across the room.
        WebView.setWebContentsDebuggingEnabled(true)

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
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!url.startsWith("http")) return
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({ checkLeanbackBooted() }, BOOT_CHECK_DELAY_MS)
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val uri: Uri = request.url

                // Old-WebView fallback: rewrite the top-level document so the
                // stripper is the first script in it. See DocumentRewriter for
                // why onPageStarted injection is not good enough.
                if (!documentStartSupported() &&
                    request.isForMainFrame &&
                    request.method.equals("GET", ignoreCase = true) &&
                    isYouTube(uri)
                ) {
                    DocumentRewriter.rewriteMainDocument(
                        request,
                        adStripperSource(),
                        currentUserAgent()
                    )?.let { return it }
                }

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

    private fun isYouTube(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return host == "youtube.com" || host.endsWith(".youtube.com")
    }

    /** The UA the WebView would itself send for the current phase. */
    private fun currentUserAgent(): String =
        if (handshakeUaActive) TV_CLIENT_UA else TV_UA

    private fun documentStartSupported(): Boolean =
        documentStartInstalled

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
        if (documentStartFeatureAvailable()) {
            try {
                WebViewCompat.addDocumentStartJavaScript(webView, source, ALLOWED_ORIGINS)
                Log.i(TAG, "Ad stripper installed at document start (preferred path)")
                documentStartInstalled = true
            } catch (e: Exception) {
                // A rejected origin rule would otherwise crash onCreate. Log it
                // and let the document-rewrite fallback carry the load instead.
                Log.w(TAG, "addDocumentStartJavaScript rejected (${e.message}); using rewrite fallback")
            }
        } else {
            Log.w(
                TAG,
                "DOCUMENT_START_SCRIPT unsupported on this WebView " +
                    "(${currentWebViewVersion()}); using document-rewrite fallback"
            )
        }
    }

    /**
     * androidx.webkit queries the WebView provider to answer this, and on a very
     * old or unusual provider that lookup can throw rather than return false.
     * Treat any failure as "not available" and let the rewrite fallback run.
     */
    private fun documentStartFeatureAvailable(): Boolean = try {
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    } catch (e: Throwable) {
        Log.w(TAG, "WebView feature probe failed (${e.message}); assuming unsupported")
        false
    }

    private fun currentWebViewVersion(): String = try {
        WebViewCompat.getCurrentWebViewPackage(this)?.let { "${it.packageName} ${it.versionName}" }
            ?: "unknown"
    } catch (e: Throwable) {
        "unknown"
    }

    private var cachedSource: String? = null

    private fun adStripperSource(): String {
        cachedSource?.let { return it }
        val js = assets.open("yourtoba.js").bufferedReader().use { it.readText() }
        val prelude = "window.__yourtobaSpoof4K = $SPOOF_4K;\n" +
            "window.__yourtobaCodecPolicy = '$CODEC_POLICY';\n"
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
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO) {
            toggleStats()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun toggleStats() {
        statsVisible = !statsVisible
        stats.visibility = if (statsVisible) View.VISIBLE else View.GONE
        statsHandler.removeCallbacksAndMessages(null)
        if (statsVisible) pollStats()
        Log.i(TAG, "Stats overlay ${if (statsVisible) "on" else "off"}")
    }

    private fun pollStats() {
        if (!statsVisible) return
        webView.evaluateJavascript("window.__yourtobaStats && window.__yourtobaStats()") { raw ->
            stats.text = formatStats(raw)
            Log.i(TAG, "stats: ${raw?.take(300)}")
        }
        statsHandler.postDelayed({ pollStats() }, STATS_POLL_MS)
    }

    /** The JS returns a JSON string, itself delivered as a quoted JS string. */
    private fun formatStats(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return "no stats yet"
        val json = try {
            org.json.JSONObject(org.json.JSONTokener(raw).nextValue().toString())
        } catch (e: Exception) {
            return "stats unavailable"
        }
        return buildString {
            append("YourTOBA playback\n")
            for (key in json.keys()) {
                append(key).append(": ").append(json.optString(key)).append('\n')
            }
            append("(Menu to hide)")
        }
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
                if (diagnostic.visibility == View.VISIBLE) {
                    diagnostic.visibility = View.GONE
                    webView.reload()
                } else {
                    webView.evaluateJavascript("window.__yourtobaKey($KEYCODE_ESCAPE_DOM)", null)
                }
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * Leanback replaces the document with a large element tree once it boots. A
     * near-empty body after this long means it never started, which on an old
     * WebView is what happens: YouTube's own bootstrap scripts get refused and
     * nothing renders. Confirmed on WebView 55, where a plain WebView with no
     * involvement from this app fails exactly the same way.
     */
    private fun checkLeanbackBooted() {
        webView.evaluateJavascript(
            "(function(){try{return document.getElementsByTagName('*').length}catch(e){return -1}})()"
        ) { result ->
            val count = result?.trim('"')?.toIntOrNull() ?: -1
            if (count in 0..150) {
                Log.w(TAG, "Leanback did not boot (only $count elements); showing diagnostic")
                showDiagnostic(count)
            } else {
                Log.i(TAG, "Leanback booted ($count elements)")
                diagnostic.visibility = View.GONE
            }
        }
    }

    private fun showDiagnostic(elementCount: Int) {
        diagnostic.text = buildString {
            append("YourTOBA cannot render YouTube on this device.\n\n")
            append("The ad blocking is working. The problem is the browser engine:\n")
            append(currentWebViewVersion())
            append("\n\nYouTube's TV interface needs a newer WebView than this one. ")
            append("Its own start-up scripts are refused by this engine, so the page ")
            append("stays empty. Nothing this app does can work around that.\n\n")
            append("Try updating Amazon WebView, or Chrome, from the Appstore. ")
            append("If there is no update, this device is too old for a WebView-based ")
            append("YouTube client, and a native one such as SmartTube is the way to go.\n\n")
            append("(Press Back to retry, hold Back to exit. Elements rendered: $elementCount)")
        }
        diagnostic.visibility = View.VISIBLE
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
        handler.removeCallbacksAndMessages(null)
        statsHandler.removeCallbacksAndMessages(null)
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
