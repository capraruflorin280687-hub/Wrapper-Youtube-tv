// Strips YouTube's own ad placements out of the innertube JSON before the
// player or feed ever sees them, instead of trying to block/skip an ad that
// already started. Same technique VacuumTube (MIT, github.com/shy1132/VacuumTube)
// validated for youtube.com/tv.
//
// Requires contextIsolation:false on the WebContentsView so these patches
// land on the page's real window, not an isolated copy — same trade-off
// VacuumTube makes, with nodeIntegration left off so the page itself still
// has no Node access.

const IS_TV = process.argv.includes('--youtoba-tv')
const AD_OBJECT_KEYS = ['adPlacements', 'adSlots', 'playerAds', 'adBreakHeartbeatParams']
const AD_RENDERER_KEYS = [
  'adSlotRenderer',
  'promotedSparklesWebRenderer',
  'promotedVideoRenderer',
  'displayAdRenderer',
  'carouselAdRenderer',
  'compactPromotedVideoRenderer',
  'inFeedAdLayoutRenderer',
  'adBadgeRenderer'
]
const MAX_DEPTH = 14

function isAdEntry(item) {
  if (!item || typeof item !== 'object') return false
  if (AD_RENDERER_KEYS.some((key) => key in item)) return true
  if (item.command?.reelWatchEndpoint?.adClientParams?.isAd) return true
  return false
}

function stripAds(node, depth = 0) {
  if (depth > MAX_DEPTH || node === null || typeof node !== 'object') return node

  if (Array.isArray(node)) {
    for (let i = node.length - 1; i >= 0; i--) {
      if (isAdEntry(node[i])) {
        node.splice(i, 1)
      } else {
        stripAds(node[i], depth + 1)
      }
    }
    return node
  }

  for (const key of Object.keys(node)) {
    if (AD_OBJECT_KEYS.includes(key)) {
      node[key] = []
      continue
    }
    stripAds(node[key], depth + 1)
  }
  return node
}

// First paint: youtube.com assigns `var ytInitialData = {...}` and
// `var ytInitialPlayerResponse = {...}` as plain object-literal globals —
// never through JSON.parse or fetch, so this is the only thing that catches
// them. A configurable accessor defined ahead of time still intercepts that
// assignment (a top-level `var x = v` resolves through the existing
// property's setter), so we strip in place right there. This is a one-time
// property guard, not a persistent function override, which is exactly why
// it stays safe on the Desktop view below where the persistent JSON.parse/
// fetch patches don't (see IS_TV gate).
function guardInitialGlobal(name) {
  let value
  try {
    Object.defineProperty(window, name, {
      configurable: true,
      enumerable: true,
      get() {
        return value
      },
      set(v) {
        try {
          value = stripAds(v)
        } catch {
          value = v
        }
      }
    })
  } catch {
    // already defined by the page (unlikely this early) — nothing to guard
  }
}

guardInitialGlobal('ytInitialData')
guardInitialGlobal('ytInitialPlayerResponse')

// Subsequent SPA navigation: browse/search/watch-next/player calls made
// with XHR or JSON.parse'd manually. This is a *persistent* override that
// stays active for the page's whole lifetime — fine on youtube.com/tv
// (VacuumTube ships the same technique there), but on www.youtube.com it
// collided with something in Google's own anti-tampering JS on SPA
// navigation: a synthetic "SyntaxError: Identifier 'JSONPath' has already
// been declared" followed by a "RangeError: Maximum call stack size
// exceeded" (Object.apply recursing into itself) took down Polymer's
// template rendering mid-navigation, which is why the player never
// mounted. Leanback doesn't carry desktop's bot-detection machinery, so
// this stays TV-only; Desktop relies on the one-time property guards above
// plus Ghostery's network-level blocking instead.
if (IS_TV) {
  const originalJSONParse = JSON.parse
  JSON.parse = function (...args) {
    const result = originalJSONParse.apply(this, args)
    if (result && typeof result === 'object') {
      try {
        return stripAds(result)
      } catch {
        return result
      }
    }
    return result
  }

  // Same calls made via fetch().then(r => r.json()) bypass JSON.parse
  // entirely (Response.json() is implemented natively), so patch fetch too.
  // Only the specific endpoints below actually carry ad data — matching on
  // a bare "/youtubei/" substring also caught long-lived/streaming
  // endpoints under that path (live chat, polling continuations), and
  // awaiting response.clone().text() on one of those hangs forever. The
  // explicit timeout below is a second layer of protection.
  const AD_BEARING_ENDPOINTS = ['/youtubei/v1/player', '/youtubei/v1/browse', '/youtubei/v1/search', '/youtubei/v1/next']
  const FETCH_PATCH_TIMEOUT_MS = 4000

  const withTimeout = (promise, ms) =>
    new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('timed out')), ms)
      promise.then(
        (v) => {
          clearTimeout(timer)
          resolve(v)
        },
        (e) => {
          clearTimeout(timer)
          reject(e)
        }
      )
    })

  const originalFetch = window.fetch.bind(window)
  window.fetch = async function (...args) {
    const response = await originalFetch(...args)
    const url = typeof args[0] === 'string' ? args[0] : args[0]?.url || ''
    if (!AD_BEARING_ENDPOINTS.some((endpoint) => url.includes(endpoint))) return response

    try {
      const text = await withTimeout(response.clone().text(), FETCH_PATCH_TIMEOUT_MS)
      let json
      try {
        json = JSON.parse(text)
      } catch {
        return response
      }
      const stripped = stripAds(json)
      const body = JSON.stringify(stripped)

      // The rebuilt body's byte length differs from the original (ads
      // stripped out, plus fetch already transparently decompressed it) —
      // carrying over the original content-length/content-encoding headers
      // left the consumer waiting on bytes that would never arrive.
      const headers = new Headers(response.headers)
      headers.delete('content-length')
      headers.delete('content-encoding')

      return new Response(body, {
        status: response.status,
        statusText: response.statusText,
        headers
      })
    } catch {
      return response
    }
  }
}

// Gamepad support (TV view only). Leanback has no native Gamepad API
// handling of its own — VacuumTube (MIT, github.com/shy1132/VacuumTube)
// found the same and works around it by polling navigator.getGamepads()
// and synthesizing the same keydown/keyup events a physical d-pad/arrow
// press would produce. Leanback's key handler reads `.keyCode` off *any*
// Event object, not just a real KeyboardEvent (whose keyCode is read-only
// on a constructed instance) — that's why a plain `new Event(...)` with
// keyCode set works here, same trick VacuumTube uses.
if (IS_TV) {
  setupGamepadSupport()
}

function setupGamepadSupport() {
  const BUTTON_KEYCODES = {
    0: 13, // A / cross -> Enter
    1: 27, // B / circle -> Escape
    12: 38, // D-pad up
    13: 40, // D-pad down
    14: 37, // D-pad left
    15: 39 // D-pad right
  }
  const REPEAT_DELAY = 500
  const REPEAT_INTERVAL = 100

  const pressed = {}
  let repeatTimer = null

  function dispatch(type, keyCode) {
    const event = new Event(type)
    event.keyCode = keyCode
    document.dispatchEvent(event)
  }

  function down(id, keyCode) {
    if (pressed[id]) return
    pressed[id] = true
    dispatch('keydown', keyCode)
    clearTimeout(repeatTimer)
    clearInterval(repeatTimer)
    repeatTimer = setTimeout(() => {
      repeatTimer = setInterval(() => dispatch('keydown', keyCode), REPEAT_INTERVAL)
    }, REPEAT_DELAY)
  }

  function up(id, keyCode) {
    if (!pressed[id]) return
    pressed[id] = false
    dispatch('keyup', keyCode)
    clearTimeout(repeatTimer)
    clearInterval(repeatTimer)
  }

  function setAxisState(id, active, keyCode) {
    if (active) down(id, keyCode)
    else up(id, keyCode)
  }

  function poll() {
    if (document.hasFocus()) {
      for (const gamepad of navigator.getGamepads()) {
        if (!gamepad || !gamepad.connected) continue
        const gid = gamepad.index

        gamepad.buttons.forEach((button, i) => {
          const keyCode = BUTTON_KEYCODES[i]
          if (!keyCode) return
          const id = `btn${gid}_${i}`
          if (button.pressed) down(id, keyCode)
          else up(id, keyCode)
        })

        const x = gamepad.axes[0] || 0
        const y = gamepad.axes[1] || 0
        setAxisState(`ax${gid}_left`, x < -0.5, 37)
        setAxisState(`ax${gid}_right`, x > 0.5, 39)
        setAxisState(`ax${gid}_up`, y < -0.5, 38)
        setAxisState(`ax${gid}_down`, y > 0.5, 40)
      }
    }
    requestAnimationFrame(poll)
  }

  requestAnimationFrame(poll)
}
