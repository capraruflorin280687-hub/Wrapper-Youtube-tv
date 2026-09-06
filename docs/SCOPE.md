# YourTOBA: Fire TV 4K port

> **Correction, 2026-09-06:** section 2 below asserts that a Fire TV Stick 4K
> runs Fire OS 7 (Android 9, API 28). That is wrong for the first-generation
> 4K stick, which runs **Fire OS 6 (Android 7.1, API 25)** and is not getting
> Fire OS 7. Building against `minSdk 28` on that assumption produced an APK
> that could not be installed at all. `minSdk` is now 21. The lesson is the one
> already in the standing instructions: the device's actual OS version was
> checkable and I asserted it instead.
>
> **Status, 2026-09-06:** implemented. Phases 1 to 3 below are done and the APK
> builds. This document is kept as the historical scoping record, written before
> any code and before the project was named YourTOBA; the reasoning still holds,
> and `README.md` describes what actually shipped. Two things changed during the
> build, both recorded in the README: `MAX_DEPTH` was raised from 14 to 24 after
> measuring real innertube payloads, and the 4K viewport spoof ships off by
> default. Phase 0, confirming the stick is not Vega OS, is still outstanding
> and is the only real gate left.

Scoping document. Written 2026-09-06, before any implementation.

Goal, in Jose's words: the TV-oriented interface only, with the ad-blocking,
as a sideloadable `.apk` for a Fire TV Stick 4K. **Ad-blocking is the point.**
Everything else is negotiable.

---

## 1. Verdict

**Feasible, and smaller than it sounds.** Roughly 2 to 3 days of work for a
solid v0.1, plus a half-day spike first.

The important finding: this is not a port in the usual sense. Electron does not
run on Android, so none of the shell survives. But the shell was never the
valuable part. The thing that actually kills video ads is `preload.js`, which is
~150 lines of plain browser JavaScript with no Electron API calls in the
ad-blocking path. It transfers **verbatim** into an Android WebView.

What has to be rebuilt is the container around it, and that container is a
single Kotlin activity holding a single WebView. Small.

Feasibility is not theoretical. [`ycngmn/NoTubeTV`](https://github.com/ycngmn/NoTubeTV)
(381 stars, GPL-3.0) is a shipping Android TV app that loads `youtube.com/tv` in
a stock Android WebView with a Cobalt user agent and injects an ad-blocking
userscript. It proves every load-bearing assumption. See section 6, including
the two places where our approach should deliberately differ from theirs.

---

## 2. Step zero: which Fire TV 4K is it?

This is the one thing that can kill the project outright, and it takes 30
seconds to check.

Amazon's **Fire TV Stick 4K Select** (October 2025) and **Fire TV Stick HD**
(April 2026) run **Vega OS**, not Android. Vega cannot sideload at all. No
`.apk`, no developer options, no ADB. If the stick is one of those, this project
is over and the answer is a different device.

Every earlier Fire TV Stick 4K and 4K Max runs Fire OS 7 (Android 9, API 28) or
Fire OS 8 (Android 11, API 30), and those sideload fine.

**Check:** Settings → My Fire TV → About. Read the device name and the Fire OS
version. Anything saying Fire OS 7 or 8 is good. Anything saying Vega is not.

While in there: Settings → My Fire TV → Developer Options → enable ADB
Debugging. If Developer Options is not visible, highlight the device name under
About and press select seven times.

---

## 3. What transfers from the Mac app

| YouTOBA piece | Fate on Fire TV |
|---|---|
| `stripAds()` + `AD_OBJECT_KEYS` + `AD_RENDERER_KEYS` | **Verbatim.** This is the asset. |
| `JSON.parse` / `fetch` innertube patch | **Verbatim**, and always on (see 5.1) |
| `guardInitialGlobal()` for `ytInitialData` | **Verbatim**, and finally reliable (see 5.2) |
| Two-phase Cobalt TV user agent | Port the idea, probably simplify to one UA (see 7.3) |
| `@ghostery/adblocker-electron` | **Replace.** Electron-only API. See 4.1. |
| Gamepad polling shim | **Delete.** See 5.3. |
| Desktop mode, toggle, `shell.html`, titlebar | **Delete.** Out of scope by request. |
| yt-dlp + ffmpeg downloader | **Delete.** macOS binaries, large Android lift, not the point. |
| Chrome/Brave/Firefox/Safari cookie import | **Delete.** TV device-link sign-in works natively. |

Note how much of the Mac app's complexity was Desktop-mode complexity. Cutting
Desktop removes the cookie import, the titlebar, the forced-reload router
workaround, and the whole reason the JSON patches had to be conditionally gated.
The TV-only brief is not a reduced version of YouTOBA. It is the clean version.

---

## 4. Target architecture

One Kotlin activity. One WebView. Three layers of ad defence, in order of how
much they matter.

### 4.1 Layer 1: network blocking (replaces Ghostery)

`WebViewClient.shouldInterceptRequest()` returning an empty `WebResourceResponse`
for URLs matching a bundled rule set: `doubleclick.net`, `googleadservices`,
`googlesyndication`, `/pagead/`, `/ptracking`, `/api/stats/ads`, and friends.

Worth being honest about what this layer does and does not do. It kills
trackers and banner-type ad requests. It **cannot** kill video ads, because
YouTube streams ad video from the same `googlevideo.com` hosts as real content.
There is no URL pattern that separates them. This is exactly why the Mac app's
README says the response stripping is what does the real work, and it is why
layer 2 is the one that matters.

### 4.2 Layer 2: innertube response stripping (the one that matters)

`preload.js`'s `stripAds` walk, injected into the page **before any YouTube
script runs**, via `WebViewCompat.addDocumentStartJavaScript()` from
`androidx.webkit`. That API is the exact Android equivalent of an Electron
preload: guaranteed to execute before page scripts, scoped to an origin
allowlist.

Ads are removed from YouTube's own JSON before the player ever sees them, so
there is no ad to skip. Same technique, same code, different host.

Fallback if that API is unavailable on Fire OS's WebView build (see 7.2):
intercept the main document in `shouldInterceptRequest`, and rewrite the HTML to
inject the patch as the first `<script>` in `<head>`. Version-independent,
works on any WebView, slightly more code.

### 4.3 Layer 3: none needed

No cosmetic filtering. The Mac app disabled it because it crashed YouTube's JS,
and that finding carries over unchanged.

### 4.4 The rest of the app

- Leanback manifest: `LEANBACK_LAUNCHER` intent category and a 320x180 banner
  drawable, or it will not appear on the Fire TV home row at all.
- `android.hardware.touchscreen` declared `required="false"`.
- Back key mapped to Escape (keyCode 27) into the page, exiting the app only
  when Leanback is already at its home screen.
- Viewport spoof to 3840x2160 plus a compensating initial scale, which is how
  NoTubeTV gets Leanback to render at 4K and fit the screen.
- Hardware layer, `mediaPlaybackRequiresUserGesture = false`, keep screen on,
  DOM storage and cookies enabled.
- `minSdk 28`, no native libraries, so the ABI question never arises.

---

## 5. Where the Android version is better than the Mac one

Not a consolation prize. Three real improvements.

**5.1 The JSON patches stop being conditional.** On Mac, the persistent
`JSON.parse`/`fetch` overrides had to be gated behind `IS_TV`, because on
`www.youtube.com` they collided with Google's anti-tampering JS and took down
Polymer mid-navigation. There is no Desktop view here. The validated TV path is
the only path, so the strongest form of the ad-blocking runs unconditionally.

**5.2 First-paint stripping gets reliable.** `guardInitialGlobal` depends
entirely on defining the accessor before YouTube assigns `ytInitialData`.
`addDocumentStartJavaScript` guarantees that ordering.

**5.3 Input gets simpler and better.** The gamepad shim exists because Leanback
has no Gamepad API handling, so the Mac app polls `navigator.getGamepads()` and
synthesizes key events. On Fire TV the remote's D-pad already arrives as real
Android key events, which the WebView already translates into real DOM key
events. The whole shim deletes. Only the Back button needs an explicit mapping.

---

## 6. Prior art: NoTubeTV

Worth reading before writing a line. It validates the architecture, and two of
its choices are ones we should deliberately not copy.

**Do not copy: injection timing.** NoTubeTV injects its userscript on
`LoadingState.Finished`, meaning after page load completes. That is a race
against YouTube's own scripts and it cannot catch `ytInitialData` at all.
YouTOBA's document-start approach is strictly stronger, and it is the whole
reason `preload.js` works as well as it does on the Mac.

**Do not copy: remote script fetching.** NoTubeTV downloads its ad-block
userscript from `raw.githubusercontent.com` at every launch, in an infinite
retry loop with no timeout. That means the app hangs on the splash screen
forever if GitHub is unreachable, and it is remote code execution by design.
Bundle the script in the APK. Optionally check for updates, never block on it.

**Do copy: the viewport trick**, which is the non-obvious answer to getting
Leanback to render at 4K on a TV, and **the manifest shape**, which is the
correct Leanback declaration.

**Also worth knowing:** [SmartTube](https://github.com/yuliskov/SmartTube) is
the mature, popular, native ad-blocking YouTube client for Fire TV. It is not a
WebView wrapper; it speaks the innertube API directly. If the goal were purely
"watch YouTube without ads on the Fire TV tonight", SmartTube is the honest
answer and it is free. The reasons to build YourTOBA anyway are that it is
Jose's own code end to end, it reuses ad-blocking he already wrote and
understands, and it has no third-party trust surface. NoTubeTV is 15 months
stale as of today, and SmartTube's build environment was compromised once
(versions 30.43 to 30.47), which is a decent argument for owning the thing.

---

## 7. Risks

**7.1 Vega OS.** Project-ending if the stick is a 4K Select or 2026 HD. Checked
in 30 seconds. See section 2. Everything below assumes Fire OS 7 or 8.

**7.2 Fire OS WebView version.** Amazon ships its own Chromium fork and updates
it on its own schedule, so `addDocumentStartJavaScript` may not be supported on
an older Fire OS 7 stick. Detectable at runtime with
`WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)`, and
checkable up front with `adb shell dumpsys package com.amazon.webview.chromium`.
Mitigated, not blocked: the HTML-rewrite fallback in 4.2 works on any version.
Build both paths and pick at runtime.

**7.3 Playback performance.** The real unknown. A 2018 Fire TV Stick 4K is a
modest quad-A53, and a WebView is heavier than Cobalt's native player. NoTubeTV
users report it working, but "works on Android TV" is not "smooth at 4K60 on
this specific stick". This is the main thing the spike exists to answer, and the
reason to test on the actual device before building anything else. If playback
disappoints, the two levers are UA-based quality capping and dropping the 4K
viewport spoof to 1080p.

**7.4 Maintenance tail.** `AD_RENDERER_KEYS` is a list of YouTube's internal
renderer names. YouTube renames and adds those. Expect to update the list
occasionally, which is the same tail the Mac app already has.

**7.5 Sideload friction.** Amazon tightened sideloading during 2026. Still
possible via ADB over the network on Fire OS devices, just fussier.

**7.6 Toolchain.** This machine has no JDK and no Android SDK. Needs JDK 17 plus
`cmdline-tools`, platform 34/35, and build-tools. Roughly 3 GB. Android Studio
is not required; Gradle CLI is enough.

---

## 8. Plan

**Phase 0, gate (Jose, 30 seconds).** Confirm the stick is not Vega. Enable ADB
debugging and note the IP.

**Phase 1, toolchain (1 to 2 hours).** JDK 17, `cmdline-tools`, SDK packages,
`adb connect` to the stick.

**Phase 2, spike (half a day).** The smallest possible APK: one activity, one
WebView, Cobalt UA, `youtube.com/tv`, `preload.js` injected at document start,
nothing else. Sideload it and answer three questions on the real hardware.
1. Does Leanback load and play?
2. Do video ads actually not appear?
3. Is playback smooth, and at what resolution?

If those three land, the rest is ordinary work. If playback is bad, we learn it
on day one instead of day three.

**Phase 3, v0.1 (2 to 3 days).** Network blocking layer, Back-key bridge,
viewport and scaling, Leanback manifest with banner and icon, exit handling,
release signing, and a README carrying over the ad-blocking explanation from the
Mac app.

**Explicitly out of scope:** downloads, Desktop mode, cookie import,
SponsorBlock. Downloads in particular are a large Android lift for something
Jose said is not the point.

---

## 9. Recommendation

Build it fresh rather than forking NoTubeTV. The Kotlin host is a few hundred
lines, the ad-blocking is already written and already Jose's, and forking would
mean inheriting GPL-3.0, a Compose and ktor dependency stack, a self-updater, a
downloader, a SponsorBlock integration, and a remote-script-loading design that
should be removed anyway. Read it, take the viewport trick and the manifest,
write the rest.

Next action is Phase 0, then say go and Phase 1 and 2 can run in one sitting.
