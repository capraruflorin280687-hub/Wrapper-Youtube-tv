package com.yourtoba

/**
 * TV build: YouTube's Leanback interface, driven by a remote.
 */
object Flavor {
    const val START_URL = "https://www.youtube.com/tv"

    /**
     * youtube.com/tv is gated to console and TV clients. These two strings come
     * from VacuumTube by way of the macOS app, along with its hard-won finding:
     * an up-to-date Cobalt version on every request causes playback issues, but
     * works fine for the one-time initial handshake. So the first navigation
     * uses the client string and everything after uses the ongoing one.
     */
    const val HANDSHAKE_UA =
        "Mozilla/5.0 (PS4; Leanback Shell) Cobalt/19.lts.0-qa; compatible; YourTOBA/0.1.0"
    const val ONGOING_UA =
        "Mozilla/5.0 (PS4; Leanback Shell) Cobalt/25.lts.40.1035033; compatible; YourTOBA/0.1.0"

    /** Leanback needs the two-phase Cobalt handshake; the mobile site does not. */
    const val TWO_PHASE_UA = true

    /** A remote has no touchscreen, so the page must never scroll on its own. */
    const val IMMERSIVE = true

    /** Back sends Escape into Leanback rather than walking history. */
    const val BACK_SENDS_ESCAPE = true

    /**
     * H.264 only by default. A first-generation Fire TV Stick 4K has no AV1
     * decoder and older Fire TV sticks do not hardware-decode VP9 either.
     */
    const val DEFAULT_CODEC_POLICY = "h264-only"
}
