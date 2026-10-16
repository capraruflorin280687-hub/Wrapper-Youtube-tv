package com.yourtoba

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
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG = "YourTOBA"

        private val START_URL = Flavor.START_URL

        // youtube.com/tv (Leanback) is gated to console/TV clients. These two UA
        // strings come from VacuumTube (MIT, github.com/shy1132/VacuumTube) by way
        // of the macOS app, along with its hard-won finding: an up-to-date Cobalt
        // version on every request triggers playback issues, but works fine for
        // the one-time initial handshake. So the "client" string is used for the
        // very first navigation only, and the "ongoing" one for everything after.
        // Empty on the phone build, where the WebView's own UA is used instead.

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
         * Which video codecs the page may claim it can play, in cycle order.
         *
         * The default is h264-only, not the more conservative-sounding no-av1.
         * A first-generation Fire TV Stick 4K has no AV1 hardware decoder, and
         * older Fire TV sticks do not hardware-decode VP9 either, so H.264 is
         * the only codec that is certain to reach the hardware decoder. Blocking
         * AV1 alone made things worse rather than better, because everything
         * then landed on VP9. This is the same reasoning behind h264ify.
         *
         *   "h264-only" block AV1 and VP9, leaving H.264 (default)
         *   "no-av1"    block AV1 only
         *   "all"       no filtering
         */
        private val CODEC_POLICIES = listOf("h264-only", "no-av1", "all")

        private const val PREFS = "yourtoba"
        private const val PREF_CODEC = "codec_policy_v2"
        private const val PREF_LAYER = "layer_mode"

        /**
         * LAYER_TYPE_NONE is the important one and was missing before.
         *
         * LAYER_TYPE_HARDWARE renders the WebView into an offscreen hardware
         * texture, and a hole-punched video SurfaceView cannot composite into an
         * offscreen layer, which produces exactly this app's symptom: audio
         * plays, page and controls render, video area stays black.
         * LAYER_TYPE_SOFTWARE is worse still, since it rules out accelerated
         * video altogether. NONE is the platform default and the only one of the
         * three that leaves the normal video overlay path intact.
         */
        private val LAYER_MODES = listOf("none", "hardware", "software")

        private const val SELFTEST_URL = "https://yourtoba.local/selftest.html"

        private const val STATS_POLL_MS = 1000L
    }

    private lateinit var webView: WebView
    private lateinit var diagnostic: TextView
    private lateinit var stats: TextView
    private var swipeRefresh: SwipeRefreshLayout? = null
    private var statsVisible = false
    private lateinit var prefs: android.content.SharedPreferences
    private var codecPolicy = "h264-only"
    private var layerMode = "none"
    private val handler = Handler(Looper.getMainLooper())
    private val statsHandler = Handler(Looper.getMainLooper())

    /** True until the first navigation commits, so the Cobalt handshake UA is used once. */
    private var handshakeUaActive = true

    private var blockedCount = 0

    /** False if the preferred path was unavailable or refused at install time. */
    private var documentStartInstalled = false
    private var documentStartScript: androidx.webkit.ScriptHandler? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        codecPolicy = prefs.getString(PREF_CODEC, Flavor.DEFAULT_CODEC_POLICY)
            ?: Flavor.DEFAULT_CODEC_POLICY
        layerMode = prefs.getString(PREF_LAYER, LAYER_MODES.first()) ?: LAYER_MODES.first()

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

        // Pull to refresh, phone only. It wraps the WebView so the gesture is
        // handled before the page sees it, but only in the one place that is
        // unambiguous: see canPullToRefresh below.
        val content: View = if (Flavor.PULL_TO_REFRESH) {
            SwipeRefreshLayout(this).apply {
                addView(webView, FrameLayout.LayoutParams(MATCH, MATCH))
                setColorSchemeColors(0xFFE2222A.toInt())
                setProgressBackgroundColorSchemeColor(0xFF1A1A1E.toInt())
                setOnRefreshListener {
                    Log.i(TAG, "Pull to refresh")
                    webView.reload()
                }
                // Without this the layout decides for itself whether the child
                // can scroll up, and a WebView reports that badly enough that
                // the gesture fires mid-page and eats ordinary scrolling.
                setOnChildScrollUpCallback { _, _ -> !canPullToRefresh() }
                swipeRefresh = this
            }
        } else {
            webView
        }

        setContentView(FrameLayout(this).apply {
            addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
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

        // Forcing a hardware layer is what NoTubeTV does, but an offscreen
        // hardware layer can stop a WebView's video overlay compositing, which
        // is one documented cause of "audio plays, picture stays black". It is
        // switchable rather than assumed, because it cannot be reproduced here.
        applyLayerType()
        webView.isVerticalScrollBarEnabled = !Flavor.IMMERSIVE
        webView.isHorizontalScrollBarEnabled = false
        webView.addJavascriptInterface(Bridge(), "YourTOBA")

        installAdStripper()

        // HTML5 video in a WebView is meant to have a WebChromeClient. Without
        // one, a page asking to go fullscreen gets no answer, and some WebView
        // builds decline to render video at all. It also gives us the page's
        // console in logcat, which is the only window into what Leanback's
        // player is complaining about on a TV across the room.
        webView.webChromeClient = object : WebChromeClient() {
            private var customView: View? = null

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                // A fullscreen player fills the screen; a stray downward drag
                // there should scrub or dismiss, never reload the page.
                swipeRefresh?.isEnabled = false
                customView = view
                (window.decorView as android.view.ViewGroup).addView(
                    view,
                    android.view.ViewGroup.LayoutParams(MATCH, MATCH)
                )
                goFullscreen()
            }

            override fun onHideCustomView() {
                swipeRefresh?.isEnabled = Flavor.PULL_TO_REFRESH
                customView?.let {
                    (window.decorView as android.view.ViewGroup).removeView(it)
                }
                customView = null
                goFullscreen()
            }

            // WebView paints its own gray placeholder (a stretched play icon)
            // until the first video frame arrives. Return a black pixel so the
            // first seconds of every video are black instead.
            override fun getDefaultVideoPoster(): android.graphics.Bitmap? {
                val bmp = android.graphics.Bitmap.createBitmap(
                    1, 1, android.graphics.Bitmap.Config.ARGB_8888
                )
                bmp.eraseColor(Color.BLACK)
                return bmp
            }

            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR ||
                    m.message().contains("video", true) ||
                    m.message().contains("codec", true) ||
                    m.message().contains("decode", true)
                ) {
                    Log.w(TAG, "page: ${m.message().take(220)}")
                }
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (Flavor.TWO_PHASE_UA && handshakeUaActive) {
                    // The main document request has already gone out with the
                    // handshake UA. Everything from here (subresources, innertube
                    // XHRs, media) uses the ongoing UA, which mirrors how the Mac
                    // app sets the session UA and overrides only the first load.
                    handshakeUaActive = false
                    view.settings.userAgentString = Flavor.ONGOING_UA
                    Log.i(TAG, "Handshake done, switched to ongoing Cobalt UA")
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                swipeRefresh?.isRefreshing = false
                if (!url.startsWith("http")) return
                if (url.contains("yourtoba.local")) return
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({ checkLeanbackBooted() }, BOOT_CHECK_DELAY_MS)
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val uri: Uri = request.url

                // The self-test is served from assets under an https origin
                // rather than file://, so the page is a normal secure origin and
                // canvas can read the decoded video without tainting.
                if (uri.host == "yourtoba.local") {
                    serveAsset(uri.lastPathSegment)?.let { return it }
                }

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

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                if (request.isForMainFrame) swipeRefresh?.isRefreshing = false
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

        webView.settings.userAgentString =
            if (Flavor.TWO_PHASE_UA) Flavor.HANDSHAKE_UA else mobileUserAgent()
        webView.loadUrl(START_URL)
    }

    /**
     * True only when a downward drag can mean nothing but "reload".
     *
     * Two things have to hold. The page must already be at the very top, or the
     * gesture would steal ordinary scrolling. And we must not be in Shorts,
     * where a vertical swipe is how you move between videos: hijacking that
     * would break the feature rather than add one.
     */
    private fun canPullToRefresh(): Boolean {
        if (webView.scrollY > 0) return false
        val url = webView.url ?: return true
        return !url.contains("/shorts", ignoreCase = true)
    }

    private fun applyLayerType() {
        webView.setLayerType(
            when (layerMode) {
                "hardware" -> View.LAYER_TYPE_HARDWARE
                "software" -> View.LAYER_TYPE_SOFTWARE
                else -> View.LAYER_TYPE_NONE
            },
            null
        )
        Log.i(TAG, "Layer type: $layerMode")
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

    private fun serveAsset(name: String?): WebResourceResponse? {
        val safe = name?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]+")) } ?: return null
        val mime = when {
            safe.endsWith(".html") -> "text/html"
            safe.endsWith(".mp4") -> "video/mp4"
            safe.endsWith(".js") -> "application/javascript"
            else -> "application/octet-stream"
        }
        return try {
            WebResourceResponse(mime, "utf-8", 200, "OK", HashMap(), assets.open(safe))
        } catch (e: Exception) {
            Log.w(TAG, "asset $safe missing: ${e.message}")
            null
        }
    }

    private fun isYouTube(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return host == "youtube.com" || host.endsWith(".youtube.com")
    }

    /** The UA the WebView would itself send for the current phase. */
    private fun currentUserAgent(): String = when {
        !Flavor.TWO_PHASE_UA -> mobileUserAgent()
        handshakeUaActive -> Flavor.HANDSHAKE_UA
        else -> Flavor.ONGOING_UA
    }

    /**
     * The WebView's own user agent with the "; wv" token removed, which is the
     * only thing distinguishing it from Chrome for Android. YouTube serves a
     * degraded page to anything it reads as an embedded WebView.
     *
     * Deliberately derived rather than hardcoded: Chromium builds its Sec-CH-UA
     * client hints from the real engine version whatever setUserAgentString
     * says, so a fixed string would drift out of step with the hints beside it,
     * and that disagreement is exactly what Google's checks look for.
     */
    private fun mobileUserAgent(): String =
        webView.settings.userAgentString
            .replace("; wv)", ")")
            .replace(" wv)", ")")

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
                documentStartScript =
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
        // note: cleared by changeCodecPolicy so a new policy actually ships
        val js = assets.open("yourtoba.js").bufferedReader().use { it.readText() }
        val prelude = "window.__yourtobaSpoof4K = $SPOOF_4K;\n" +
            "window.__yourtobaCodecPolicy = '$codecPolicy';\n"
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
    /**
     * Overlay controls have to be handled here rather than in onKeyDown. The
     * WebView holds focus and consumes d-pad keys for Leanback navigation, so
     * Activity.onKeyDown never sees them. dispatchKeyEvent runs before the view
     * hierarchy gets a look.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN

        if (event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_INFO) {
            if (down) toggleStats()
            return true
        }

        if (statsVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) cycleCodecPolicy(1); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) cycleCodecPolicy(-1); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { if (down) cycleLayerMode(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) cycleLayerMode(1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (down) openSelfTest(); return true
                }
            }
        }

        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            event.startTracking()
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

    private fun cycleCodecPolicy(step: Int) {
        val i = CODEC_POLICIES.indexOf(codecPolicy).coerceAtLeast(0)
        codecPolicy = CODEC_POLICIES[((i + step) % CODEC_POLICIES.size + CODEC_POLICIES.size) % CODEC_POLICIES.size]
        prefs.edit().putString(PREF_CODEC, codecPolicy).apply()
        Log.i(TAG, "Codec policy -> $codecPolicy (reloading)")
        cachedSource = null
        reinstallAndReload()
    }

    private fun cycleLayerMode(step: Int) {
        val i = LAYER_MODES.indexOf(layerMode).coerceAtLeast(0)
        layerMode = LAYER_MODES[((i + step) % LAYER_MODES.size + LAYER_MODES.size) % LAYER_MODES.size]
        prefs.edit().putString(PREF_LAYER, layerMode).apply()
        applyLayerType()
        reinstallAndReload()
    }

    /** Loads the bundled self-test, which answers decode versus compositing. */
    private fun openSelfTest() {
        statsVisible = false
        stats.visibility = View.GONE
        diagnostic.visibility = View.GONE
        Log.i(TAG, "Opening video self-test")
        webView.loadUrl(SELFTEST_URL)
    }

    /**
     * The stripper is injected at document start, so a settings change only
     * takes effect once the script is re-registered and the page reloaded.
     */
    private fun reinstallAndReload() {
        stats.text = "applying..."
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            documentStartScript?.remove()
            documentStartScript = null
            documentStartInstalled = false
        }
        installAdStripper()
        webView.reload()
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
            append("layer: ").append(layerMode).append('\n')
            append("\nLeft/Right: codec policy\n")
            append("Up/Down: layer type\n")
            append("Select: run video self-test\n")
            append("Menu: hide")
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
                if (webView.url?.contains("yourtoba.local") == true) {
                    webView.loadUrl(START_URL)
                } else if (diagnostic.visibility == View.VISIBLE) {
                    diagnostic.visibility = View.GONE
                    webView.reload()
                } else if (Flavor.BACK_SENDS_ESCAPE) {
                    webView.evaluateJavascript("window.__yourtobaKey($KEYCODE_ESCAPE_DOM)", null)
                } else if (webView.canGoBack()) {
                    // Phone: Back walks history like any Android browser.
                    webView.goBack()
                } else {
                    finish()
                }
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * YouTube replaces the document with a large element tree once it boots. A
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
                Log.w(TAG, "Page did not render (only $count elements); showing diagnostic")
                showDiagnostic(count)
            } else {
                Log.i(TAG, "Page rendered ($count elements)")
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
        if (!Flavor.IMMERSIVE) return
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

        @JavascriptInterface
        fun selfTestResult(summary: String) {
            Log.i(TAG, "SELFTEST: $summary")
        }
    }
}
