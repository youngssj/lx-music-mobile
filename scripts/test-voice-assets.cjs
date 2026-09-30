const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const root = path.join(__dirname, '..')
const service = fs.readFileSync(path.join(root, 'android/app/src/main/java/cn/toside/music/mobile/voice/VoiceService.kt'), 'utf8')
const prompts = JSON.parse(fs.readFileSync(path.join(root, 'src/resources/voice/prompts.json'), 'utf8'))
const feedback = fs.readFileSync(path.join(root, 'android/app/src/main/java/cn/toside/music/mobile/voice/VoiceFeedback.kt'), 'utf8')
for (const key of Object.keys(prompts)) {
  const resource = 'voice_' + key.toLowerCase()
  assert.ok(feedback.includes(`"${key}" to R.raw.${resource}`), `Missing native prompt: ${key}`)
  // Verify the bundled spoken reply is playable PCM, rather than an empty WAV header.
  const reply = fs.readFileSync(path.join(root, 'android/app/src/main/res/raw', resource + '.wav'))
  assert.equal(reply.toString('ascii', 0, 4), 'RIFF')
  assert.equal(reply.toString('ascii', 8, 12), 'WAVE')
  assert.equal(reply.readUInt32LE(4) + 8, reply.length)
  let bytesPerSecond = 0
  let audioBytes = 0
  for (let offset = 12; offset + 8 <= reply.length;) {
    const type = reply.toString('ascii', offset, offset + 4)
    const size = reply.readUInt32LE(offset + 4)
    assert.ok(offset + 8 + size <= reply.length, 'Truncated reply audio')
    if (type === 'fmt ') {
      assert.equal(reply.readUInt16LE(offset + 8), 1, 'Reply must use PCM')
      bytesPerSecond = reply.readUInt32LE(offset + 16)
    }
    if (type === 'data') {
      audioBytes += size
      assert.ok(reply.subarray(offset + 8, offset + 8 + size).some(value => value !== 0), 'Reply is silent')
    }
    offset += 8 + size + (size % 2)
  }
  assert.ok(bytesPerSecond > 0)
  assert.ok(audioBytes / bytesPerSecond > 0.5 && audioBytes / bytesPerSecond < 9, `Unexpected prompt duration: ${key}`)
}
assert.ok(service.includes('keywordsFile = "voice/kws/keywords.txt"'))
assert.ok(service.includes('stream = spotter.createStream()'))
assert.ok(service.includes('"loading" -> null') && service.includes('"listening" -> null'), 'Startup must remain silent')
const keywords = fs.readFileSync(path.join(root, 'android/app/src/main/assets/voice/kws/keywords.txt'), 'utf8').trim()
assert.ok(keywords.length > 0)
const tokenPath = path.join(root, 'android/app/src/main/assets/voice/kws/tokens.txt')
if (fs.existsSync(tokenPath)) {
  const tokens = new Set(fs.readFileSync(tokenPath, 'utf8').trim().split(/\r?\n/).map(line => line.split(/\s+/)[0]))
  for (const line of keywords.split(/\r?\n/)) {
    for (const token of line.split(/\s+/).filter(token => !/^[@:#]/.test(token))) {
      assert.ok(tokens.has(token), `Unknown wake-word token: ${token}`)
    }
  }
}
console.log(`Voice asset checks passed: ${Object.keys(prompts).length} bundled speech clips and keyword initialization.`)
