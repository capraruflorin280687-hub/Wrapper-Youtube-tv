package com.yourtoba

/**
 * Phone build: YouTube's ordinary mobile site, driven by touch.
 */
object Flavor {
    const val START_URL = "https://m.youtube.com/"

    /**
     * Left empty so the WebView's own user agent is used, with the "; wv" token
     * stripped (see MainActivity.mobileUserAgent). That keeps the Chrome version
     * truthful, which matters: Chromium generates its Sec-CH-UA client hints
     * from the real engine version regardless of what setUserAgentString says,
     * so a hardcoded string would disagree with the hints it sends. A mismatch
     * there is exactly the inconsistency Google's checks look for.
     */
    const val HANDSHAKE_UA = ""
    const val ONGOING_UA = ""

    const val TWO_PHASE_UA = false

    /** A phone keeps its status bar and its ability to scroll. */
    const val IMMERSIVE = false

    /** Back walks the site's history, the way every Android browser behaves. */
    const val BACK_SENDS_ESCAPE = false

    /** Drag down at the top of the page to reload, as any mobile app would. */
    const val PULL_TO_REFRESH = true

    /**
     * No filtering. A phone from any recent year decodes AV1 and VP9 fine, and
     * blocking them would only force worse quality at higher bitrate.
     */
    const val DEFAULT_CODEC_POLICY = "all"
}
