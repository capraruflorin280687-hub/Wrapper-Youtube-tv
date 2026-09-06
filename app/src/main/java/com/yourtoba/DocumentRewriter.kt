package com.yourtoba

import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.util.Base64
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.net.URL
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Fallback injection for WebViews too old for
 * WebViewCompat.addDocumentStartJavaScript (anything before Chromium 105,
 * which includes some Fire OS 7 builds).
 *
 * Why this exists rather than the obvious `evaluateJavascript` in
 * onPageStarted: onPageStarted fires before the new document has committed, so
 * the script executes against the document being navigated AWAY from. Observed
 * directly on an Android TV API 28 emulator (WebView 66) where the injection
 * reported itself running on "about:blank" and the real YouTube page got no ad
 * stripping at all. Silently doing nothing is worse than not trying, so the
 * fallback fetches the main document itself and returns it with the stripper
 * inlined as the first script in the document. That ordering is guaranteed by
 * construction, on any WebView version.
 *
 * Only the top-level HTML document is ever intercepted. Everything else,
 * including every innertube API call and all media, is left completely alone.
 * If anything at all goes wrong, this returns null and the WebView performs its
 * own normal load, so a failure here degrades to "no injection" and never to a
 * broken page.
 */
object DocumentRewriter {

    private const val TAG = MainActivity.TAG
    private const val TIMEOUT_MS = 15_000

    fun rewriteMainDocument(
        request: WebResourceRequest,
        script: String,
        userAgent: String
    ): WebResourceResponse? {
        val urlString = request.url.toString()
        return try {
            val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", userAgent)
                // Ask for plain bytes. Handling gzip is possible but pointless
                // here: this is one document per navigation, and identity keeps
                // the rewrite honest.
                setRequestProperty("Accept-Encoding", "identity")
                for ((k, v) in request.requestHeaders) {
                    if (k.equals("Accept-Encoding", true)) continue
                    if (k.equals("User-Agent", true)) continue
                    setRequestProperty(k, v)
                }
                CookieManager.getInstance().getCookie(urlString)?.let {
                    setRequestProperty("Cookie", it)
                }
            }

            val status = conn.responseCode
            val contentType = conn.contentType ?: ""
            if (status !in 200..299 || !contentType.lowercase(Locale.ROOT).contains("text/html")) {
                Log.d(TAG, "rewrite skipped (status=$status type=$contentType)")
                conn.disconnect()
                return null
            }

            val raw = (if ((conn.contentEncoding ?: "").contains("gzip", true)) {
                GZIPInputStream(conn.inputStream)
            } else {
                conn.inputStream
            }).use { it.readBytes() }

            val charset = charsetFrom(contentType)
            val html = String(raw, charset)

            // WebView does not apply Set-Cookie from an intercepted response, so
            // hand them to the CookieManager by hand or sign-in silently breaks.
            val finalUrl = conn.url.toString()
            conn.headerFields["Set-Cookie"]?.forEach { cookie ->
                CookieManager.getInstance().setCookie(finalUrl, cookie)
            }

            // YouTube serves script-src with a nonce, so a bare inline <script>
            // is refused outright ("Refused to execute inline script"). Observed
            // on the API 28 emulator: the HTML was rewritten correctly and the
            // browser simply would not run it. Reuse the page's own nonce so the
            // policy stays exactly as strict as YouTube intended.
            val cspValues = conn.headerFields.entries
                .filter { it.key?.equals("Content-Security-Policy", true) == true }
                .flatMap { it.value ?: emptyList() }
            val nonce = findNonce(cspValues, html)

            val injected = inject(html, script, nonce)
            if (injected == null) {
                Log.w(TAG, "rewrite skipped: no injection point in document")
                conn.disconnect()
                return null
            }

            val scriptHash = if (nonce == null) sha256Base64(script) else null

            val headers = HashMap<String, String>()
            for ((k, v) in conn.headerFields) {
                if (k == null) continue
                // These describe the original body, which no longer exists.
                if (k.equals("Content-Length", true)) continue
                if (k.equals("Content-Encoding", true)) continue
                if (k.equals("Set-Cookie", true)) continue
                var value = v.joinToString(", ")
                // No nonce to borrow: authorise exactly this one script by its
                // hash. Nothing else about the policy changes.
                if (scriptHash != null && k.equals("Content-Security-Policy", true)) {
                    value = addHashToScriptSrc(value, scriptHash)
                }
                headers[k] = value
            }
            conn.disconnect()

            Log.i(
                TAG,
                "Ad stripper inlined into document (fallback path, " +
                    (if (nonce != null) "nonce" else "hash") + "): $finalUrl"
            )

            WebResourceResponse(
                "text/html",
                charset.name(),
                200,
                "OK",
                headers,
                ByteArrayInputStream(injected.toByteArray(charset))
            )
        } catch (e: Exception) {
            Log.w(TAG, "rewrite failed, falling back to normal load: ${e.message}")
            null
        }
    }

    /**
     * Puts the script before anything the page can run. Preference order is
     * immediately after <head>, then before the first <script>, then after
     * <html>. Returns null if the document has none of those, which would mean
     * it is not the page we think it is.
     */
    private fun inject(html: String, script: String, nonce: String?): String? {
        val nonceAttr = if (nonce != null) " nonce=\"$nonce\"" else ""
        val tag = "<script$nonceAttr>\n$script\n</script>"

        Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)?.let {
            return html.substring(0, it.range.last + 1) + tag + html.substring(it.range.last + 1)
        }
        Regex("<script", RegexOption.IGNORE_CASE).find(html)?.let {
            return html.substring(0, it.range.first) + tag + html.substring(it.range.first)
        }
        Regex("<html[^>]*>", RegexOption.IGNORE_CASE).find(html)?.let {
            return html.substring(0, it.range.last + 1) + tag + html.substring(it.range.last + 1)
        }
        return null
    }

    /** Pulls a script nonce out of the CSP headers, or failing that the HTML. */
    private fun findNonce(cspValues: List<String>, html: String): String? {
        val fromHeader = Regex("script-src[^;]*'nonce-([^']+)'", RegexOption.IGNORE_CASE)
        for (v in cspValues) {
            fromHeader.find(v)?.let { return it.groupValues[1] }
        }
        // Some responses carry the policy as a meta tag instead, and YouTube
        // stamps the same nonce onto its own script tags either way.
        return Regex("<script[^>]+nonce=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
    }

    private fun sha256Base64(script: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(script.toByteArray())
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    /** Adds a 'sha256-...' source to every script-src directive in a policy. */
    private fun addHashToScriptSrc(csp: String, hash: String): String {
        if (!csp.contains("script-src", ignoreCase = true)) return csp
        return csp.split(";").joinToString(";") { part ->
            if (part.trim().startsWith("script-src", ignoreCase = true)) {
                "$part 'sha256-$hash'"
            } else {
                part
            }
        }
    }

    private fun charsetFrom(contentType: String): java.nio.charset.Charset {
        val m = Regex("charset=([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE).find(contentType)
        return try {
            m?.groupValues?.get(1)?.let { java.nio.charset.Charset.forName(it) } ?: Charsets.UTF_8
        } catch (e: Exception) {
            Charsets.UTF_8
        }
    }
}
