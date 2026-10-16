// YourTOBA ad stripping.
//
// Ported from YouTOBA's preload.js (the macOS Electron app) essentially
// verbatim. Strips YouTube's own ad placements out of the innertube JSON
// before the player or feed ever sees them, instead of trying to block or
// skip an ad that already started. Technique validated by VacuumTube
// (MIT, github.com/shy1132/VacuumTube) for youtube.com/tv.
//
// On macOS this ran as an Electron preload with contextIsolation:false so the
// patches landed on the page's real window. The Android equivalent is
// WebViewCompat.addDocumentStartJavaScript, which runs in the page's main
// world before any page script executes. Same guarantee, same result.
//
// Difference worth noting versus the Mac app: there, the persistent
// JSON.parse/fetch overrides had to be gated behind an IS_TV check, because on
// www.youtube.com they collided with Google's anti-tampering JS and took down
// Polymer mid-navigation. YourTOBA is Leanback-only, so the strongest form of
// the stripping runs unconditionally. There is no Desktop view to break.

(function () {
  'use strict'

  if (window.__yourtobaInstalled) return
  window.__yourtobaInstalled = true

  var AD_OBJECT_KEYS = ['adPlacements', 'adSlots', 'playerAds', 'adBreakHeartbeatParams']
  var AD_RENDERER_KEYS = [
    // From the macOS app, seen on the TV and desktop surfaces.
    'adSlotRenderer',
    'promotedSparklesWebRenderer',
    'promotedVideoRenderer',
    'displayAdRenderer',
    'carouselAdRenderer',
    'compactPromotedVideoRenderer',
    'inFeedAdLayoutRenderer',
    'adBadgeRenderer',
    // m.youtube.com uses its own set. The signed-out feed carries no ads, so
    // these could not be confirmed against a live response the way the TV keys
    // were; they are the renderers the mobile site is known to use for promoted
    // slots and upsells. Each one names an ad container, so removing the whole
    // entry removes the ad rather than leaving an empty shell.
    'promotedSparklesTextRenderer',
    'compactPromotedItemRenderer',
    'searchPyvRenderer',
    'mealbarPromoRenderer',
    'bannerPromoRenderer',
    'statementBannerRenderer',
    'brandVideoShelfRenderer',
    'brandVideoSingletonRenderer',
    'videoMastheadAdV3Renderer',
    'primetimePromoRenderer',
    'adsEngagementPanelContentRenderer'
  ]
  // Depth measured against real responses rather than guessed at. A TVHTML5
  // browse feed nests to 19; the m.youtube.com home feed nests to 30. The Mac
  // app's 14 truncated both. 40 clears the deepest thing observed with room to
  // spare.
  //
  // The cap is only a runaway guard, and the thing it actually needs to guard
  // against is a cycle, not depth, so `seen` below does that job directly. That
  // is why this number can be generous instead of being nudged upward every
  // time YouTube adds a wrapper.
  //
  // Video ads never needed any of this: in a real /youtubei/v1/player response
  // adPlacements, playerAds and adBreakHeartbeatParams all sit at depth 0.
  var MAX_DEPTH = 40

  function isAdEntry(item) {
    if (!item || typeof item !== 'object') return false
    for (var i = 0; i < AD_RENDERER_KEYS.length; i++) {
      if (AD_RENDERER_KEYS[i] in item) return true
    }
    if (
      item.command &&
      item.command.reelWatchEndpoint &&
      item.command.reelWatchEndpoint.adClientParams &&
      item.command.reelWatchEndpoint.adClientParams.isAd
    ) {
      return true
    }
    return false
  }

  var HAS_WEAKSET = typeof WeakSet === 'function'

  function stripAds(node, depth, seen) {
    depth = depth || 0
    if (depth > MAX_DEPTH || node === null || typeof node !== 'object') return node

    // Guard the real hazard. Parsed JSON is always a tree, but the property
    // guards below can be handed a live page object, and one back-reference
    // there would otherwise recurse until the stack gives out.
    if (HAS_WEAKSET) {
      if (!seen) seen = new WeakSet()
      if (seen.has(node)) return node
      seen.add(node)
    }

    if (Array.isArray(node)) {
      for (var i = node.length - 1; i >= 0; i--) {
        if (isAdEntry(node[i])) {
          node.splice(i, 1)
        } else {
          stripAds(node[i], depth + 1, seen)
        }
      }
      return node
    }

    var keys = Object.keys(node)
    for (var k = 0; k < keys.length; k++) {
      var key = keys[k]
      if (AD_OBJECT_KEYS.indexOf(key) !== -1) {
        node[key] = []
        continue
      }
      stripAds(node[key], depth + 1, seen)
    }
    return node
  }

  // First paint: YouTube assigns `var ytInitialData = {...}` and
  // `var ytInitialPlayerResponse = {...}` as plain object-literal globals,
  // never through JSON.parse or fetch, so this is the only thing that catches
  // them. A configurable accessor defined ahead of time still intercepts that
  // assignment (a top-level `var x = v` resolves through the existing
  // property's setter), so we strip in place right there.
  //
  // On macOS this was a race the preload happened to win. Here
  // addDocumentStartJavaScript guarantees we are defined first.
  function guardInitialGlobal(name) {
    var value
    try {
      Object.defineProperty(window, name, {
        configurable: true,
        enumerable: true,
        get: function () {
          return value
        },
        set: function (v) {
          try {
            value = stripAds(v)
          } catch (e) {
            value = v
          }
        }
      })
    } catch (e) {
      // already defined by the page (unlikely this early) - nothing to guard
    }
  }

  // On m.youtube.com these globals are assigned a hex-escaped JSON *string*
  // rather than an object literal, so the guards below see a string and pass it
  // straight through. That is correct and not a gap: the page then calls
  // JSON.parse on it, and the JSON.parse patch further down strips it there.
  guardInitialGlobal('ytInitialData')
  guardInitialGlobal('ytInitialPlayerResponse')

  // Subsequent SPA navigation: browse/search/watch-next/player calls made with
  // XHR or JSON.parse'd manually.
  var originalJSONParse = JSON.parse
  JSON.parse = function () {
    var result = originalJSONParse.apply(this, arguments)
    if (result && typeof result === 'object') {
      try {
        return stripAds(result)
      } catch (e) {
        return result
      }
    }
    return result
  }

  // Same calls made via fetch().then(r => r.json()) bypass JSON.parse entirely
  // (Response.json() is implemented natively), so patch fetch too. Only the
  // endpoints below actually carry ad data: matching on a bare "/youtubei/"
  // substring also caught long-lived streaming endpoints under that path (live
  // chat, polling continuations), and awaiting response.clone().text() on one
  // of those hangs forever. The explicit timeout is a second layer of guard.
  var AD_BEARING_ENDPOINTS = [
    '/youtubei/v1/player',
    '/youtubei/v1/browse',
    '/youtubei/v1/search',
    '/youtubei/v1/next'
  ]
  var FETCH_PATCH_TIMEOUT_MS = 4000

  function withTimeout(promise, ms) {
    return new Promise(function (resolve, reject) {
      var timer = setTimeout(function () {
        reject(new Error('timed out'))
      }, ms)
      promise.then(
        function (v) {
          clearTimeout(timer)
          resolve(v)
        },
        function (e) {
          clearTimeout(timer)
          reject(e)
        }
      )
    })
  }

  if (window.fetch) {
    var originalFetch = window.fetch.bind(window)
    window.fetch = function () {
      var args = arguments
      return originalFetch.apply(null, args).then(function (response) {
        var first = args[0]
        var url = typeof first === 'string' ? first : (first && first.url) || ''

        var interesting = false
        for (var i = 0; i < AD_BEARING_ENDPOINTS.length; i++) {
          if (url.indexOf(AD_BEARING_ENDPOINTS[i]) !== -1) {
            interesting = true
            break
          }
        }
        if (!interesting) return response

        return withTimeout(response.clone().text(), FETCH_PATCH_TIMEOUT_MS)
          .then(function (text) {
            var json
            try {
              json = originalJSONParse(text)
            } catch (e) {
              return response
            }
            var body = JSON.stringify(stripAds(json))

            // The rebuilt body's byte length differs from the original (ads
            // stripped out, plus fetch already transparently decompressed it),
            // so carrying over the original content-length/content-encoding
            // left the consumer waiting on bytes that would never arrive.
            var headers = new Headers(response.headers)
            headers['delete']('content-length')
            headers['delete']('content-encoding')

            return new Response(body, {
              status: response.status,
              statusText: response.statusText,
              headers: headers
            })
          })
          .catch(function () {
            return response
          })
      })
    }
  }

  // ---------------------------------------------------------------------
  // Codec policy.
  //
  // Chromium ships a software AV1 decoder, so a WebView answers "yes, I can
  // play AV1" even on hardware that has no AV1 decoder at all. YouTube believes
  // it and serves AV1, and a first-generation Fire TV Stick 4K then tries to
  // decode it on a quad Cortex-A53. The result is exactly what was reported:
  // audio with a black screen when the decoder gives up entirely, or heavy
  // stutter when it half keeps up, while the occasional lighter video plays.
  //
  // That stick hardware-decodes H.264, HEVC and VP9, but not AV1. So the fix is
  // to stop claiming AV1 support. Leanback asks three different ways and all
  // three have to agree, or it will still pick a format we cannot play.
  //
  // Policy comes from the native side:
  //   "no-av1"    block AV1 only, leaving VP9 and H.264 (default)
  //   "h264-only" also block VP9, for a device whose VP9 is unreliable
  //   "all"       no filtering, i.e. the old behaviour
  var CODEC_POLICY = window.__yourtobaCodecPolicy || 'no-av1'
  var BLOCKED_CODECS =
    CODEC_POLICY === 'h264-only' ? ['av01', 'vp9', 'vp09'] :
    CODEC_POLICY === 'all' ? [] : ['av01']

  function isBlockedType(type) {
    if (!type || !BLOCKED_CODECS.length) return false
    var t = String(type).toLowerCase()
    for (var i = 0; i < BLOCKED_CODECS.length; i++) {
      if (t.indexOf(BLOCKED_CODECS[i]) !== -1) return true
    }
    return false
  }

  if (BLOCKED_CODECS.length) {
    // 1. MediaSource.isTypeSupported, which is what MSE players check first.
    if (window.MediaSource && MediaSource.isTypeSupported) {
      var originalIsTypeSupported = MediaSource.isTypeSupported.bind(MediaSource)
      MediaSource.isTypeSupported = function (type) {
        if (isBlockedType(type)) return false
        return originalIsTypeSupported(type)
      }
    }

    // 2. canPlayType, the older query. "" means not supported.
    if (window.HTMLMediaElement && HTMLMediaElement.prototype.canPlayType) {
      var originalCanPlayType = HTMLMediaElement.prototype.canPlayType
      HTMLMediaElement.prototype.canPlayType = function (type) {
        if (isBlockedType(type)) return ''
        return originalCanPlayType.call(this, type)
      }
    }

    // 3. Media Capabilities, which is what a modern Leanback prefers because it
    //    also reports "smooth" and "powerEfficient".
    //    Guarded on `typeof` rather than assuming the global exists: every
    //    patch in this file shares one script, so an unguarded reference to a
    //    missing global would throw and take the ad blocking down with it.
    var nav = typeof navigator !== 'undefined' ? navigator : null
    if (nav && nav.mediaCapabilities && nav.mediaCapabilities.decodingInfo) {
      var originalDecodingInfo =
        nav.mediaCapabilities.decodingInfo.bind(nav.mediaCapabilities)
      nav.mediaCapabilities.decodingInfo = function (config) {
        var contentType = config && config.video && config.video.contentType
        if (isBlockedType(contentType)) {
          return Promise.resolve({
            supported: false,
            smooth: false,
            powerEfficient: false
          })
        }
        return originalDecodingInfo(config)
      }
    }
  }

  // Records what actually ends up playing, so the stats overlay can report the
  // real codec rather than what we hoped for.
  window.__yourtobaCodecs = []
  if (window.MediaSource && MediaSource.prototype.addSourceBuffer) {
    var originalAddSourceBuffer = MediaSource.prototype.addSourceBuffer
    MediaSource.prototype.addSourceBuffer = function (type) {
      try {
        if (window.__yourtobaCodecs.indexOf(type) === -1) {
          window.__yourtobaCodecs.push(type)
        }
      } catch (e) {}
      return originalAddSourceBuffer.call(this, type)
    }
  }

  /** Playback stats for the on-screen overlay and for logcat. */
  window.__yourtobaStats = function () {
    try {
      var v = document.querySelector('video')
      var out = {
        policy: CODEC_POLICY,
        blocked: BLOCKED_CODECS.join(',') || 'none',
        codecs: (window.__yourtobaCodecs || [])
          .map(function (c) { return c.replace('video/', '').replace('audio/', '') })
          .join('  ')
      }
      if (!v) {
        out.video = 'no video element'
        return JSON.stringify(out)
      }
      out.size = v.videoWidth + 'x' + v.videoHeight
      out.state = v.paused ? 'paused' : 'playing'
      out.time = Math.round(v.currentTime) + 's'
      if (v.getVideoPlaybackQuality) {
        var q = v.getVideoPlaybackQuality()
        var total = q.totalVideoFrames || 0
        var dropped = q.droppedVideoFrames || 0
        out.frames = dropped + ' dropped / ' + total
        out.dropPct = total ? Math.round((dropped / total) * 1000) / 10 + '%' : '0%'
      }
      return JSON.stringify(out)
    } catch (e) {
      return JSON.stringify({ error: String(e) })
    }
  }

  // Called from the native side when the Fire TV remote's Back button is
  // pressed. Leanback reads `.keyCode` off any Event object, not just a real
  // KeyboardEvent (whose keyCode is read-only on a constructed instance), so a
  // plain Event with keyCode set works. Same trick the Mac app's gamepad shim
  // used, which is the only part of that shim worth keeping: on Fire TV the
  // remote's d-pad already arrives as real DOM key events.
  window.__yourtobaKey = function (keyCode) {
    try {
      ;['keydown', 'keyup'].forEach(function (type) {
        var event = new Event(type, { bubbles: true, cancelable: true })
        event.keyCode = keyCode
        event.which = keyCode
        document.dispatchEvent(event)
      })
      return true
    } catch (e) {
      return false
    }
  }

  // Optional 4K viewport spoof, off by default. The native side sets this flag
  // before this script runs. Leanback picks its layout (and, reportedly, the
  // stream resolution it is willing to ask for) from the viewport, so forcing
  // 3840x2160 is how other Leanback shells get 4K. It is off by default because
  // the compensating scale factor is display-dependent and cannot be verified
  // without the actual TV: get it wrong and the UI is unusable. Ad blocking
  // does not depend on it at all.
  if (window.__yourtobaSpoof4K) {
    var applyViewport = function () {
      if (!document.head) return
      var meta = document.querySelector('meta[name="viewport"]')
      if (!meta) {
        meta = document.createElement('meta')
        meta.name = 'viewport'
        document.head.appendChild(meta)
      }
      meta.setAttribute('content', 'width=3840, height=2160, initial-scale=1.0')
    }
    if (document.head) applyViewport()
    else document.addEventListener('DOMContentLoaded', applyViewport)
  }

  // ---------------------------------------------------------------------
  // Logo swap: replaces the YouTube logo (top right) with a "Premium" badge.
  //
  // The logo is drawn by YouTube's own page, so it is found at runtime: first
  // by a few likely selectors, then by a size/position heuristic (a wide,
  // short svg/img/background in the top-right corner). The original is hidden
  // and a red-icon + "Premium" SVG takes its exact place. A MutationObserver
  // redoes it whenever YouTube re-renders the header. Wrapped in try/catch so
  // a failure here can never take the ad stripping above down with it.
  try {
    var PREMIUM_TEXT = 'Premium'
    var BADGE_ATTR = 'data-yourtoba-premium'

    var makeBadge = function (w, h) {
      var ns = 'http://www.w3.org/2000/svg'
      var svg = document.createElementNS(ns, 'svg')
      svg.setAttribute(BADGE_ATTR, '1')
      svg.setAttribute('viewBox', '0 0 195 45')
      svg.setAttribute('preserveAspectRatio', 'xMaxYMid meet')
      svg.setAttribute('width', String(Math.round(w)))
      svg.setAttribute('height', String(Math.round(h)))
      svg.style.flex = 'none'
      svg.style.display = 'block'
      var icon = document.createElementNS(ns, 'rect')
      icon.setAttribute('x', '0'); icon.setAttribute('y', '2')
      icon.setAttribute('width', '60'); icon.setAttribute('height', '41')
      icon.setAttribute('rx', '11'); icon.setAttribute('fill', '#ff0033')
      var tri = document.createElementNS(ns, 'path')
      tri.setAttribute('d', 'M24 13 L24 32 L41 22.5 Z')
      tri.setAttribute('fill', '#ffffff')
      var txt = document.createElementNS(ns, 'text')
      txt.setAttribute('x', '68'); txt.setAttribute('y', '34')
      txt.setAttribute('fill', '#ffffff')
      txt.setAttribute('font-family', 'Roboto, Arial, sans-serif')
      txt.setAttribute('font-weight', '700')
      txt.setAttribute('font-size', '36')
      txt.setAttribute('textLength', '127')
      txt.setAttribute('lengthAdjust', 'spacingAndGlyphs')
      txt.textContent = PREMIUM_TEXT
      svg.appendChild(icon); svg.appendChild(tri); svg.appendChild(txt)
      return svg
    }

    var looksLikeLogo = function (el) {
      if (!el || el.getAttribute(BADGE_ATTR) || el.closest('[data-yourtoba-orig]') || el.closest('[' + BADGE_ATTR + ']')) return false
      var r = el.getBoundingClientRect()
      var w = r.width, h = r.height
      if (w < 60 || w > 400 || h < 15 || h > 100) return false
      var ratio = w / h
      if (ratio < 2.2 || ratio > 6.5) return false
      if (r.top > window.innerHeight * 0.2) return false
      if (r.left < window.innerWidth * 0.55) return false
      return true
    }

    var findLogo = function (deepScan) {
      var hits = []
      var list = document.querySelectorAll(
        'ytlr-logo-entity, [class*="logo" i], svg, img, yt-icon, ytlr-icon'
      )
      var i
      for (i = 0; i < list.length; i++) {
        if (looksLikeLogo(list[i])) hits.push(list[i])
      }
      if (!hits.length && deepScan && document.body) {
        var all = document.body.getElementsByTagName('*')
        for (i = 0; i < all.length; i++) {
          var el = all[i]
          if (!looksLikeLogo(el)) continue
          var bg = getComputedStyle(el).backgroundImage
          if (bg && bg !== 'none') hits.push(el)
        }
      }
      // Outermost match wins (document order lists ancestors first).
      var chosen = []
      for (i = 0; i < hits.length; i++) {
        var inside = false
        for (var j = 0; j < chosen.length; j++) {
          if (chosen[j].contains(hits[i])) { inside = true; break }
        }
        if (!inside) chosen.push(hits[i])
      }
      return chosen
    }

    // Fine-tune the badge position in CSS pixels (positive = right / down).
    var BADGE_SHIFT_X = 0
    var BADGE_SHIFT_Y = 0

    var ORIG_ATTR = 'data-yourtoba-orig'
    var tracked = []
    var trackerRunning = false

    // The original logo stays in the layout (invisible), so the page keeps its
    // spacing. The badge is a fixed overlay that follows the original's box on
    // every frame, so it sits exactly where the logo was: the right end of the
    // search bar row. It hides itself whenever the original is not shown.
    var tick = function () {
      for (var i = tracked.length - 1; i >= 0; i--) {
        var t = tracked[i]
        if (!document.documentElement.contains(t.el)) {
          if (t.badge.parentNode) t.badge.parentNode.removeChild(t.badge)
          tracked.splice(i, 1)
          continue
        }
        var r = t.el.getBoundingClientRect()
        if (r.width < 1 || r.height < 1) {
          t.badge.style.display = 'none'
          continue
        }
        t.badge.style.display = 'block'
        t.badge.style.left = Math.round(r.left + BADGE_SHIFT_X) + 'px'
        t.badge.style.top = Math.round(r.top + BADGE_SHIFT_Y) + 'px'
        t.badge.setAttribute('width', String(Math.round(r.width)))
        t.badge.setAttribute('height', String(Math.round(r.height)))
      }
      if (tracked.length) {
        requestAnimationFrame(tick)
      } else {
        trackerRunning = false
      }
    }

    var scans = 0
    var swapLogo = function () {
      try {
        scans++
        var found = findLogo(scans <= 25)
        for (var i = 0; i < found.length; i++) {
          var el = found[i]
          if (el.getAttribute(ORIG_ATTR)) continue
          var r = el.getBoundingClientRect()
          var badge = makeBadge(r.width, r.height)
          badge.style.position = 'fixed'
          badge.style.left = Math.round(r.left) + 'px'
          badge.style.top = Math.round(r.top) + 'px'
          badge.style.zIndex = '2147483647'
          badge.style.pointerEvents = 'none'
          document.body.appendChild(badge)
          el.setAttribute(ORIG_ATTR, '1')
          el.style.setProperty('visibility', 'hidden', 'important')
          tracked.push({ el: el, badge: badge })
        }
        if (tracked.length && !trackerRunning) {
          trackerRunning = true
          requestAnimationFrame(tick)
        }
      } catch (e) {}
    }

    var swapTimer = null
    var scheduleSwap = function () {
      if (swapTimer) return
      swapTimer = setTimeout(function () {
        swapTimer = null
        swapLogo()
      }, 400)
    }

    var startLogoWatch = function () {
      scheduleSwap()
      new MutationObserver(scheduleSwap).observe(document.documentElement, {
        childList: true,
        subtree: true
      })
    }
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', startLogoWatch)
    } else {
      startLogoWatch()
    }
  } catch (e) {}

  // ---------------------------------------------------------------------
  // Black player background.
  //
  // The gray behind the player (the placeholder with the play icon) is drawn
  // by YouTube's page. Two layers: static CSS for the usual player containers
  // and the video element, plus a runtime sweep that turns any large gray
  // (r = g = b, 90..200) element covering most of the screen black. Wrapped in
  // try/catch so it can never affect the ad stripping.
  try {
    var BLACK_ATTR = 'data-yourtoba-black'

    var addBlackCss = function () {
      if (document.getElementById('yourtoba-black-css')) return
      var st = document.createElement('style')
      st.id = 'yourtoba-black-css'
      st.textContent =
        'html, body, video, ytlr-player, ytlr-watch-page, ytlr-player-container,' +
        ' .html5-video-player, .html5-video-container, .video-stream,' +
        ' [class*="player" i], [class*="Player"] { background-color: #000 !important; }' +
        'video { background: #000 !important; }' +
        'video[poster] { object-fit: contain; }'
      ;(document.head || document.documentElement).appendChild(st)
    }

    var isGray = function (c) {
      var m = /^rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)$/.exec(c || '')
      if (!m) return false
      var r = +m[1], g = +m[2], b = +m[3]
      var a = m[4] === undefined ? 1 : +m[4]
      return a > 0.5 && Math.abs(r - g) < 8 && Math.abs(g - b) < 8 && r >= 90 && r <= 200
    }

    var blackenGray = function () {
      try {
        if (!document.body) return
        var vw = window.innerWidth, vh = window.innerHeight
        var all = document.body.getElementsByTagName('*')
        for (var i = 0; i < all.length; i++) {
          var el = all[i]
          if (el.getAttribute(BLACK_ATTR)) continue
          var r = el.getBoundingClientRect()
          if (r.width < vw * 0.5 || r.height < vh * 0.5) continue
          if (isGray(getComputedStyle(el).backgroundColor)) {
            el.setAttribute(BLACK_ATTR, '1')
            el.style.setProperty('background-color', '#000', 'important')
          }
        }
      } catch (e) {}
    }

    var blackTimer = null
    var scheduleBlack = function () {
      if (blackTimer) return
      blackTimer = setTimeout(function () {
        blackTimer = null
        addBlackCss()
        blackenGray()
      }, 300)
    }

    var startBlackWatch = function () {
      addBlackCss()
      scheduleBlack()
      new MutationObserver(scheduleBlack).observe(document.documentElement, {
        childList: true,
        subtree: true,
        attributes: true,
        attributeFilter: ['class', 'style']
      })
    }
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', startBlackWatch)
    } else {
      startBlackWatch()
    }
  } catch (e) {}

  if (window.YourTOBA && window.YourTOBA.onInjected) {
    try {
      window.YourTOBA.onInjected(location.href)
    } catch (e) {}
  }
})()
