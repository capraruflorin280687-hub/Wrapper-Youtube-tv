# YourTOBA

YouTube's TV interface on a Fire TV Stick, sideloaded, with the ads stripped
out of YouTube's own data before the player ever learns they exist.

The Android sibling of [YouTOBA](https://github.com/FromChaosComesClarity/youTOBA),
the macOS app. Same ad blocking, ported essentially verbatim. None of the rest.

Personal-use project, not affiliated with YouTube or Google.

> **A note on the name:** "toba" is Brazilian Portuguese slang for butthole.
> This was, allegedly, on purpose. The extra `r` was also on purpose.

## What it is

One Kotlin activity holding one WebView pointed at `youtube.com/tv`, the
Leanback interface that TVs and consoles get, with a Cobalt user agent so
YouTube serves it. Ad stripping is injected into the page before any YouTube
script runs.

Deliberately not included, because the brief was the TV interface and the ad
blocking: no downloader, no desktop mode, no browser cookie import, no
SponsorBlock.

## Requirements

- A Fire TV that runs **Fire OS**, which means Android underneath.
  **Not Vega OS.** Amazon's Fire TV Stick 4K Select (Oct 2025) and Fire TV
  Stick HD (2026) run Vega and cannot sideload anything at all. Check under
  Settings → My Fire TV → About. Fire OS 7 (Android 9) or Fire OS 8
  (Android 11) are both fine.
- ADB debugging enabled on the device (see below).
- For building: JDK 17 and the Android SDK. `env.sh` points at a userspace
  install of both, so nothing needs root.

## Install

On the Fire TV, enable Settings → My Fire TV → Developer Options → **ADB
debugging**. If Developer Options is not there, open Settings → My Fire TV →
About, highlight the device name and press select seven times. The IP address
is under About → Network.

Then, from a machine on the same network:

```bash
./install.sh 192.168.1.42          # build, connect, install, launch
./install.sh 192.168.1.42 logs     # ...and tail the app's logcat
```

Accept the "Allow USB debugging?" prompt that appears on the TV the first time,
then run it again. YourTOBA lands on the Fire TV home row like any other app.

## Using it

The remote works the way Leanback expects, because the d-pad already arrives as
real key events.

| Button | Does |
|---|---|
| D-pad, Select | Navigate, as normal |
| Back (press) | Back inside Leanback |
| Back (hold) | Exit the app |
| Home | Leaves the app, as always |

**Signing in** uses the `google.com/device` code flow, the one Google
explicitly supports for TV and limited-input apps. It just works, and it is the
reason none of the macOS app's browser-cookie-import machinery came along.

## Ad blocking

Two layers, and they are not equally important.

**1. Response stripping. This is the one that matters.** `adPlacements`,
`adSlots`, `playerAds` and known ad-renderer entries are removed directly from
YouTube's innertube JSON, via a persistent `fetch` and `JSON.parse` patch, plus
property guards on the `ytInitialData` and `ytInitialPlayerResponse` globals.
The player is handed a video that never had an ad attached, so there is no ad
to skip. Technique validated by [VacuumTube](https://github.com/shy1132/VacuumTube)
(MIT) and carried over from the macOS app.

The script is injected with `WebViewCompat.addDocumentStartJavaScript`, the
Android equivalent of an Electron preload: guaranteed to run before any of the
page's own JavaScript. That ordering guarantee is what the property guards
depend on. If the device's WebView is too old to support it, the app falls back
to injecting at `onPageStarted` and says so loudly in logcat. The fallback is
late enough to miss first paint but still lands before the innertube player
calls, which is where video ads live.

**2. Network blocking.** Ad and tracker hosts are refused in
`shouldInterceptRequest`. This replaces `@ghostery/adblocker-electron` from the
macOS app, which is Electron-only.

Worth being blunt about layer 2: it cannot stop video ads. YouTube serves ad
video from the same `googlevideo.com` hosts as real content, so no URL pattern
separates them. Layer 1 is what buys you an uninterrupted video. Layer 2 kills
trackers and banners. This is also why `AdBlockList.kt` must never block
`googlevideo.com`, and there is an explicit guard preventing it.

Cosmetic filtering is deliberately absent. On macOS it collided with YouTube's
own JS badly enough to crash the page, and that finding carried over.

### Measured, not assumed

Against real TVHTML5 innertube responses pulled on 2026-09-06:

- In a real `/youtubei/v1/player` response, `adPlacements`, `playerAds` and
  `adBreakHeartbeatParams` all sit at **depth 0**. The whole response nests only
  to depth 9. Video ad stripping has enormous margin.
- A real signed-out `/youtubei/v1/browse` home feed nests to **depth 19**. The
  macOS app's `MAX_DEPTH` of 14 truncated the walk before the bottom of a real
  feed, so a deeply nested sponsored tile could survive. Raised to 24 here, with
  a regression test at depth 19 that fails at 14 and passes at 24.

Known gap, inherited: some native and sponsored cards on the home feed use ad
formats outside what `AD_RENDERER_KEYS` currently targets and may still appear.
Those are tiles in a feed, not interruptions to a video.

## Tests

```bash
./test.sh
```

Runs the ad-stripping suite against `app/src/main/assets/yourtoba.js`, the exact
file the APK ships, inside a fake browser context. 13 cases covering the player
response, feed tiles, Shorts reel ads, the property guards, the fetch patch,
pass-through of non-ad endpoints, the depth ceiling, and the Back-key bridge.

## Building by hand

```bash
. ./env.sh
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

The APK is signed with `app/sideload.keystore`, which is committed on purpose.
This is a personal sideload build, not a Play Store release, and losing the key
would mean uninstalling and reinstalling on the TV to change signer.

## Layout

```
app/src/main/assets/yourtoba.js    the ad stripping (the actual asset)
app/src/main/java/.../MainActivity.kt   WebView host, UA handshake, key handling
app/src/main/java/.../AdBlockList.kt    network-level block rules
tests/strip.test.mjs               ad-stripping test suite
docs/SCOPE.md                      why the port is shaped this way
docs/reference-preload.js          the original macOS preload, for diffing
install.sh                         build + sideload over network ADB
```

## Not yet verified on hardware

Everything here is built and tested on a desktop. Three things need a real Fire
TV to confirm, in this order: that Leanback loads and plays at all, that video
ads genuinely do not appear, and that playback is smooth and at what resolution.
`./install.sh <ip> logs` will show which injection path was taken and what got
blocked.

There is also an optional 4K viewport spoof, `SPOOF_4K` in `MainActivity.kt`,
off by default. Leanback picks its layout from the viewport, and forcing
3840x2160 is how other Leanback shells coax 4K out of it, but the compensating
scale factor depends on the display and a wrong value makes the UI unusable.
Ad blocking does not depend on it.

## Acknowledgments

- [VacuumTube](https://github.com/shy1132/VacuumTube) (MIT), for the TV user
  agent handshake and the response-stripping technique.
- [NoTubeTV](https://github.com/ycngmn/NoTubeTV) (GPL-3.0), read as prior art
  for the Android side. Not used as a base, and its two weakest choices are
  deliberately avoided here: it injects after page load rather than at document
  start, and it downloads its ad-block script from GitHub at every launch.
- [SmartTube](https://github.com/yuliskov/SmartTube), the mature native
  alternative, if you would rather install something than maintain something.
