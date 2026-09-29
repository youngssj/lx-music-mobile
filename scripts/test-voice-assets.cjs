const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const root = path.join(__dirname, '..')
const service = fs.readFileSync(path.join(root, 'android/app/src/main/java/cn/toside/music/mobile/voice/VoiceService.kt'), 'utf8')
assert.ok(service.includes('keywordsFile = "voice/kws/keywords.txt"'))
assert.ok(service.includes('stream = spotter.createStream()'))
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
console.log('Voice keyword asset and initialization checks passed.')
