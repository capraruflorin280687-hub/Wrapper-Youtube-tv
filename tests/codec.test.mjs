// Verifies the codec policy in yourtoba.js: the page must consistently deny
// support for codecs the device cannot decode in hardware, across all three
// APIs Leanback uses to decide what to request.
//
//   node tests/codec.test.mjs

import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import assert from 'node:assert/strict'

const SRC = new URL('../app/src/main/assets/yourtoba.js', import.meta.url)
const source = readFileSync(SRC, 'utf8')

const AV1 = 'video/mp4; codecs="av01.0.05M.08"'
const VP9 = 'video/webm; codecs="vp9"'
const VP09 = 'video/mp4; codecs="vp09.00.10.08"'
const H264 = 'video/mp4; codecs="avc1.640028"'
const AAC = 'audio/mp4; codecs="mp4a.40.2"'

function makeSandbox(policy) {
  const addedBuffers = []
  const sandbox = {
    console,
    setTimeout,
    clearTimeout,
    Headers,
    Response,
    fetch: async () => new Response('{}'),
    Event: class { constructor(t) { this.type = t } },
    document: {
      head: {},
      querySelector: () => null,
      createElement: () => ({ setAttribute() {} }),
      addEventListener() {},
      dispatchEvent() { return true }
    },
    location: { href: 'https://www.youtube.com/tv' },
    navigator: {
      mediaCapabilities: {
        decodingInfo: async () => ({ supported: true, smooth: true, powerEfficient: true })
      }
    }
  }
  sandbox.window = sandbox
  sandbox.__yourtobaCodecPolicy = policy

  // Minimal MSE + media element surface.
  sandbox.MediaSource = function () {}
  sandbox.MediaSource.isTypeSupported = () => true
  sandbox.MediaSource.prototype.addSourceBuffer = function (t) {
    addedBuffers.push(t); return { type: t }
  }
  sandbox.HTMLMediaElement = function () {}
  sandbox.HTMLMediaElement.prototype.canPlayType = () => 'probably'

  vm.createContext(sandbox)
  vm.runInContext(source, sandbox, { filename: 'yourtoba.js' })
  return { sandbox, addedBuffers }
}

let pass = 0, fail = 0
function check(name, fn) {
  try { fn(); console.log(`  ok   ${name}`); pass++ }
  catch (e) { console.log(`  FAIL ${name}\n       ${e.message}`); fail++ }
}

console.log('\nyourtoba.js codec policy\n')

// ---- default policy: block AV1 only ------------------------------------
{
  const { sandbox } = makeSandbox('no-av1')
  const MS = sandbox.MediaSource
  const canPlay = sandbox.HTMLMediaElement.prototype.canPlayType

  check('no-av1: MediaSource.isTypeSupported denies AV1', () => {
    assert.equal(MS.isTypeSupported(AV1), false)
  })
  check('no-av1: VP9 and H.264 still supported', () => {
    assert.equal(MS.isTypeSupported(VP9), true)
    assert.equal(MS.isTypeSupported(VP09), true)
    assert.equal(MS.isTypeSupported(H264), true)
  })
  check('no-av1: audio is never blocked', () => {
    assert.equal(MS.isTypeSupported(AAC), true)
  })
  check('no-av1: canPlayType returns "" for AV1, unchanged otherwise', () => {
    assert.equal(canPlay.call({}, AV1), '')
    assert.equal(canPlay.call({}, H264), 'probably')
  })
}

// ---- mediaCapabilities has to agree ------------------------------------
const asyncChecks = (async () => {
  {
    const { sandbox } = makeSandbox('no-av1')
    const mc = sandbox.navigator.mediaCapabilities

    const av1 = await mc.decodingInfo({ type: 'media-source', video: { contentType: AV1 } })
    check('no-av1: decodingInfo reports AV1 unsupported and not smooth', () => {
      assert.equal(av1.supported, false)
      assert.equal(av1.smooth, false)
      assert.equal(av1.powerEfficient, false)
    })

    const vp9 = await mc.decodingInfo({ type: 'media-source', video: { contentType: VP9 } })
    check('no-av1: decodingInfo still allows VP9', () => {
      assert.equal(vp9.supported, true)
    })

    check('decodingInfo tolerates a config with no video block', async () => {
      // must not throw
      return mc.decodingInfo({ type: 'file', audio: { contentType: AAC } })
    })
  }

  // ---- stricter policy ---------------------------------------------------
  {
    const { sandbox } = makeSandbox('h264-only')
    const MS = sandbox.MediaSource
    check('h264-only: blocks AV1 and VP9, keeps H.264', () => {
      assert.equal(MS.isTypeSupported(AV1), false)
      assert.equal(MS.isTypeSupported(VP9), false)
      assert.equal(MS.isTypeSupported(VP09), false)
      assert.equal(MS.isTypeSupported(H264), true)
    })
  }

  // ---- opt out -----------------------------------------------------------
  {
    const { sandbox } = makeSandbox('all')
    const MS = sandbox.MediaSource
    check('all: no filtering, AV1 passes through', () => {
      assert.equal(MS.isTypeSupported(AV1), true)
    })
  }

  // ---- codec recording + stats ------------------------------------------
  {
    const { sandbox, addedBuffers } = makeSandbox('no-av1')
    const ms = new sandbox.MediaSource()
    ms.addSourceBuffer(VP9)
    ms.addSourceBuffer(AAC)
    ms.addSourceBuffer(VP9) // duplicate

    check('records the codecs actually used, without duplicates', () => {
      assert.equal(sandbox.window.__yourtobaCodecs.length, 2)
      assert.ok(sandbox.window.__yourtobaCodecs.includes(VP9))
      assert.ok(sandbox.window.__yourtobaCodecs.includes(AAC))
    })
    check('addSourceBuffer still delegates to the real implementation', () => {
      assert.equal(addedBuffers.length, 3)
    })
    check('stats report the policy and survive having no video element', () => {
      const s = JSON.parse(sandbox.window.__yourtobaStats())
      assert.equal(s.policy, 'no-av1')
      assert.equal(s.blocked, 'av01')
      assert.equal(s.video, 'no video element')
      assert.ok(s.codecs.includes('vp9'))
    })
  }

  console.log(`\n${pass} passed, ${fail} failed\n`)
  process.exit(fail === 0 ? 0 : 1)
})()
