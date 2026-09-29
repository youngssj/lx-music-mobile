const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ts = require('typescript')
const Module = require('node:module')

const filename = path.join(__dirname, '../src/core/voice/commands.ts')
const compiled = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
}).outputText
const mod = new Module(filename, module)
mod._compile(compiled, filename)
const parse = mod.exports.parseVoiceCommand

assert.deepEqual(parse('小洛小洛，请暂停播放。'), { action: 'pause' })
assert.deepEqual(parse('<|zh|><|NEUTRAL|>下一首！'), { action: 'skipNext' })
assert.deepEqual(parse('继续播放'), { action: 'play' })
assert.deepEqual(parse('取消收藏这首歌'), { action: 'uncollect' })
assert.deepEqual(parse('关闭语音助手'), { action: 'stopListening' })
assert.deepEqual(parse('帮我搜索晴天'), { action: 'search', query: '晴天', name: '晴天', singer: '' })
assert.deepEqual(parse('播放周杰伦的晴天'), { action: 'searchPlay', query: '周杰伦的晴天', name: '晴天', singer: '周杰伦' })
assert.deepEqual(parse('播放暂停'), { action: 'searchPlay', query: '暂停', name: '暂停', singer: '' })
assert.deepEqual(parse('播放我的天空'), { action: 'searchPlay', query: '我的天空', name: '我的天空', singer: '' })
assert.deepEqual(parse('播放夜的第七章'), { action: 'searchPlay', query: '夜的第七章', name: '夜的第七章', singer: '' })
assert.equal(parse('搜索'), null)
assert.equal(parse('这首歌很好听'), null)
assert.equal(parse(''), null)
assert.equal(parse('constructor'), null)
console.log('Voice command checks passed (14 scenarios).')
