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
    'adSlotRenderer',
    'promotedSparklesWebRenderer',
    'promotedVideoRenderer',
    'displayAdRenderer',
    'carouselAdRenderer',
    'compactPromotedVideoRenderer',
    'inFeedAdLayoutRenderer',
    'adBadgeRenderer'
  ]
  // The Mac app used 14. Measured against real TVHTML5 innertube responses on
  // 2026-09-06: a signed-out /youtubei/v1/browse home feed nests to depth 19,
  // so 14 truncates the walk before the bottom of a real feed and can leave a
  // deeply-nested sponsored tile in place. 24 clears the observed maximum with
  // headroom while still bounding runaway recursion on a cyclic structure.
  //
  // This never mattered for video ads: in a real /youtubei/v1/player response
  // adPlacements, playerAds and adBreakHeartbeatParams all sit at depth 0, and
  // the whole response only nests to 9.
  var MAX_DEPTH = 24

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

  function stripAds(node, depth) {
    depth = depth || 0
    if (depth > MAX_DEPTH || node === null || typeof node !== 'object') return node

    if (Array.isArray(node)) {
      for (var i = node.length - 1; i >= 0; i--) {
        if (isAdEntry(node[i])) {
          node.splice(i, 1)
        } else {
          stripAds(node[i], depth + 1)
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
      stripAds(node[key], depth + 1)
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

  if (window.YourTOBA && window.YourTOBA.onInjected) {
    try {
      window.YourTOBA.onInjected(location.href)
    } catch (e) {}
  }
})()
