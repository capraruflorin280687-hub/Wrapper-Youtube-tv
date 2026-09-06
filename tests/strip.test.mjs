// Verifies yourtoba.js actually removes ads from realistic innertube payloads.
// Runs the shipped asset unmodified inside a fake browser context, so what is
// tested here is byte-for-byte what the APK loads.
//
//   node tests/strip.test.mjs
//
// Harness note: the sandbox deliberately gets its OWN intrinsics (JSON, Array,
// Object) rather than sharing the host's. Sharing them meant the script's
// JSON.parse patch leaked into the test's own parsing, which both broke
// realm-sensitive assertions and made the pass-through case impossible to test.
// Node's Response.json() is JSON.parse-based (unlike a browser's native
// implementation), so an isolated realm is what keeps that distinction honest.

import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import assert from 'node:assert/strict'

const SRC = new URL('../app/src/main/assets/yourtoba.js', import.meta.url)
const source = readFileSync(SRC, 'utf8')

const listeners = {}
const documentStub = {
  head: {},
  querySelector: () => null,
  createElement: () => ({ setAttribute() {} }),
  addEventListener: (t, f) => { (listeners[t] ||= []).push(f) },
  dispatchEvent(e) { (listeners[e.type] || []).forEach((f) => f(e)); return true }
}

class EventStub {
  constructor(type) { this.type = type }
}

const FETCH_BODY = {
  playabilityStatus: { status: 'OK' },
  adPlacements: [{ adPlacementRenderer: { renderer: { linearAdSequenceRenderer: {} } } }],
  playerAds: [{ playerLegacyDesktopWatchAdsRenderer: {} }],
  streamingData: { formats: [{ itag: 18, url: 'https://r1.googlevideo.com/videoplayback?x=1' }] },
  videoDetails: { videoId: 'dQw4w9WgXcQ', title: 'Real video' }
}

// Host-side pristine parse, never patched, used to inspect raw bodies.
const rawParse = JSON.parse

const sandbox = {
  console,
  setTimeout,
  clearTimeout,
  Headers,
  Response,
  Event: EventStub,
  document: documentStub,
  location: { href: 'https://www.youtube.com/tv' },
  navigator: { mediaCapabilities: { decodingInfo: async () => ({ supported: true }) } },
  fetch: async () => new Response(JSON.stringify(FETCH_BODY), {
    status: 200,
    statusText: 'OK',
    headers: { 'content-type': 'application/json', 'content-length': '999' }
  })
}
sandbox.window = sandbox

vm.createContext(sandbox)
vm.runInContext(source, sandbox, { filename: 'yourtoba.js' })

// Handles on the sandbox's own intrinsics, after the script patched them.
const ctx = vm.runInContext('({ JSON: JSON })', sandbox)
const sjson = ctx.JSON
// Deep-clone into the sandbox realm without going through the patched parse.
const feed = (obj) => vm.runInContext(`(${JSON.stringify(obj)})`, sandbox)

let pass = 0
let fail = 0
function check(name, fn) {
  try { fn(); console.log(`  ok   ${name}`); pass++ }
  catch (e) { console.log(`  FAIL ${name}\n       ${e.message}`); fail++ }
}

function depthOfKey(node, key, d = 0) {
  if (!node || typeof node !== 'object') return -1
  if (!Array.isArray(node) && key in node) return d
  for (const v of Object.values(node)) {
    const r = depthOfKey(v, key, d + 1)
    if (r !== -1) return r
  }
  return -1
}

// Builds a payload with an ad renderer buried `depth` levels down, as an
// element of a list. That shape matters: isAdEntry only culls ad renderers that
// are array elements, because that is how they actually appear (a sponsored
// tile is an item in a shelf, and the whole item is what has to go). Nesting
// one inside plain objects would never be removed, correctly.
function nestAdAt(depth) {
  let node = [{ tileRenderer: { title: 'keep me' } }, { adSlotRenderer: { marker: true } }]
  for (let i = 0; i < depth; i++) node = { level: node }
  return { root: node }
}

console.log('\nyourtoba.js ad stripping\n')

const browseFixture = {
  contents: { tvBrowseRenderer: { content: { tvSurfaceContentRenderer: { content: {
    sectionListRenderer: { contents: [
      { shelfRenderer: { title: 'Recommended', content: { horizontalListRenderer: { items: [
        { tileRenderer: { title: 'Real one' } },
        { adSlotRenderer: { adSlotMetadata: {} } },
        { tileRenderer: { title: 'Real two' } },
        { promotedVideoRenderer: { title: 'Sponsored' } },
        { tileRenderer: { title: 'Real three' } }
      ] } } } },
      { adSlotRenderer: { fullWidth: true } },
      { shelfRenderer: { title: 'Watch again', content: { horizontalListRenderer: { items: [
        { tileRenderer: { title: 'Real four' } },
        { inFeedAdLayoutRenderer: {} }
      ] } } } }
    ] }
  } } } } }
}

check('JSON.parse empties adPlacements / adSlots / playerAds', () => {
  const out = sjson.parse(JSON.stringify({
    adPlacements: [{ a: 1 }, { b: 2 }],
    adSlots: [{ c: 3 }],
    playerAds: [{ d: 4 }],
    adBreakHeartbeatParams: 'xyz',
    videoDetails: { title: 'Real video' }
  }))
  assert.equal(out.adPlacements.length, 0)
  assert.equal(out.adSlots.length, 0)
  assert.equal(out.playerAds.length, 0)
  assert.equal(out.adBreakHeartbeatParams.length, 0)
  assert.equal(out.videoDetails.title, 'Real video')
})

check('feed: ad renderers removed, real tiles survive', () => {
  const out = sjson.parse(JSON.stringify(browseFixture))
  const sections = out.contents.tvBrowseRenderer.content.tvSurfaceContentRenderer.content.sectionListRenderer.contents
  assert.equal(sections.length, 2, 'top-level adSlotRenderer section should be gone')
  const items = sections[0].shelfRenderer.content.horizontalListRenderer.items
  assert.equal(items.length, 3)
  assert.equal(items.map((i) => i.tileRenderer.title).join(','), 'Real one,Real two,Real three')
  const items2 = sections[1].shelfRenderer.content.horizontalListRenderer.items
  assert.equal(items2.length, 1)
  assert.equal(items2[0].tileRenderer.title, 'Real four')
})

check('reel: isAd entries removed', () => {
  const out = sjson.parse(JSON.stringify({
    entries: [
      { command: { reelWatchEndpoint: { videoId: 'good' } } },
      { command: { reelWatchEndpoint: { videoId: 'ad', adClientParams: { isAd: true } } } }
    ]
  }))
  assert.equal(out.entries.length, 1)
  assert.equal(out.entries[0].command.reelWatchEndpoint.videoId, 'good')
})

check('real content is never touched', () => {
  const clean = { videoDetails: { title: 'Ad Astra', author: 'Real Channel' },
                  streamingData: { formats: [{ url: 'https://r1.googlevideo.com/x' }] } }
  const out = sjson.parse(JSON.stringify(clean))
  assert.equal(out.videoDetails.title, 'Ad Astra')
  assert.equal(out.videoDetails.author, 'Real Channel')
  assert.equal(out.streamingData.formats[0].url, 'https://r1.googlevideo.com/x')
})

check('ytInitialData assignment is stripped on the way in', () => {
  sandbox.window.ytInitialData = feed(browseFixture)
  const sections = sandbox.window.ytInitialData
    .contents.tvBrowseRenderer.content.tvSurfaceContentRenderer.content.sectionListRenderer.contents
  assert.equal(sections.length, 2)
})

check('ytInitialPlayerResponse assignment is stripped on the way in', () => {
  sandbox.window.ytInitialPlayerResponse = feed(FETCH_BODY)
  const v = sandbox.window.ytInitialPlayerResponse
  assert.equal(v.adPlacements.length, 0)
  assert.equal(v.playerAds.length, 0)
  assert.equal(v.videoDetails.title, 'Real video')
  assert.equal(v.streamingData.formats[0].url, 'https://r1.googlevideo.com/videoplayback?x=1',
    'real stream URL must survive untouched')
})

// ---- MAX_DEPTH ceiling --------------------------------------------------
check('ad at depth 13 (realistic browse nesting) is removed', () => {
  const out = sjson.parse(JSON.stringify(nestAdAt(13)))
  assert.equal(depthOfKey(out, 'adSlotRenderer'), -1, 'should have been stripped')
})

check('ad at depth 19 (deepest nesting seen in real browse data) is removed', () => {
  const out = sjson.parse(JSON.stringify(nestAdAt(19)))
  assert.equal(depthOfKey(out, 'adSlotRenderer'), -1, 'real feeds nest this deep')
})

check('KNOWN CEILING: ad deeper than MAX_DEPTH survives', () => {
  const out = sjson.parse(JSON.stringify(nestAdAt(30)))
  const d = depthOfKey(out, 'adSlotRenderer')
  assert.notEqual(d, -1, 'documents the ceiling: this ad is expected to survive')
  console.log(`       (ad at depth 30 survives, as designed; fixture browse ad sits at depth ${depthOfKey(browseFixture, 'inFeedAdLayoutRenderer')})`)
})

const _ = (async () => {
  const r = await sandbox.window.fetch('https://www.youtube.com/youtubei/v1/player?key=x')
  const bodyText = await r.text()
  const body = rawParse(bodyText)
  check('fetch: /youtubei/v1/player has ads stripped', () => {
    assert.equal(body.adPlacements.length, 0)
    assert.equal(body.playerAds.length, 0)
    assert.equal(body.videoDetails.title, 'Real video')
    assert.equal(body.streamingData.formats[0].url, 'https://r1.googlevideo.com/videoplayback?x=1')
  })
  check('fetch: rebuilt response drops stale content-length/encoding', () => {
    assert.equal(r.headers.get('content-length'), null)
    assert.equal(r.headers.get('content-encoding'), null)
  })

  const r2 = await sandbox.window.fetch('https://www.youtube.com/youtubei/v1/guide')
  const body2 = rawParse(await r2.text())
  check('fetch: non-ad-bearing endpoint passes through untouched', () => {
    assert.equal(r2.headers.get('content-length'), '999', 'original response object should be returned as-is')
    assert.equal(body2.adPlacements.length, 1, 'body must not be rewritten for this endpoint')
  })

  check('__yourtobaKey dispatches Escape with keyCode set', () => {
    const seen = []
    documentStub.addEventListener('keydown', (e) => seen.push(`keydown:${e.keyCode}`))
    documentStub.addEventListener('keyup', (e) => seen.push(`keyup:${e.keyCode}`))
    const ok = sandbox.window.__yourtobaKey(27)
    assert.equal(ok, true)
    assert.equal(seen.join(','), 'keydown:27,keyup:27')
  })

  console.log(`\n${pass} passed, ${fail} failed\n`)
  process.exit(fail === 0 ? 0 : 1)
})()
