package com.yourtoba.tv

import android.net.Uri

/**
 * Layer 1 of the ad defence: network-level blocking.
 *
 * This replaces @ghostery/adblocker-electron from the macOS app, which is an
 * Electron-only API with no Android equivalent. Rather than ship a full filter
 * engine for the handful of hosts that actually matter on Leanback, this is a
 * hand-kept list applied in WebViewClient.shouldInterceptRequest.
 *
 * Be honest about what this layer can and cannot do. It kills trackers, banner
 * ad requests and ad telemetry. It CANNOT kill video ads, because YouTube
 * streams ad video from the same googlevideo.com hosts as real content and
 * there is no URL pattern separating them. Killing video ads is what
 * yourtoba.js does by stripping the ad out of the innertube JSON before the
 * player ever learns it exists. This layer is a supporting act.
 *
 * The corollary matters when editing this file: never add googlevideo.com, and
 * never broaden a rule until you are sure it cannot match real playback or the
 * sign-in flow.
 */
object AdBlockList {

    /** Whole hosts that exist only to serve ads or tracking. */
    private val BLOCKED_HOSTS = listOf(
        "doubleclick.net",
        "googleadservices.com",
        "googlesyndication.com",
        "google-analytics.com",
        "googletagservices.com",
        "googletagmanager.com",
        "adservice.google.com",
        "2mdn.net",
        "moatads.com",
        "scorecardresearch.com"
    )

    /**
     * Path fragments on hosts we otherwise need. These are matched only on
     * Google/YouTube hosts so a video or an API call can never collide.
     */
    private val BLOCKED_PATHS = listOf(
        "/pagead/",
        "/ptracking",
        "/api/stats/ads",
        "/pcs/activeview",
        "/activeview",
        "/csi_204",
        "/gen_204"
    )

    private val AD_HOST_SUFFIXES = listOf(
        "youtube.com",
        "youtube-nocookie.com",
        "google.com",
        "googleapis.com",
        "ytimg.com"
    )

    fun shouldBlock(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        val path = uri.path?.lowercase() ?: ""

        // Never, under any circumstances, block the actual video streams.
        if (host.endsWith("googlevideo.com")) return false

        if (BLOCKED_HOSTS.any { host == it || host.endsWith(".$it") }) return true

        if (AD_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }) {
            if (BLOCKED_PATHS.any { path.contains(it) }) return true
        }

        return false
    }
}
