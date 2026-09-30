const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ts = require('typescript')
const Module = require('node:module')

const load = (relative, mocks = {}) => {
  const filename = path.join(__dirname, relative)
  const compiled = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
  }).outputText
  const mod = new Module(filename, module)
  mod.require = name => {
    if (!(name in mocks)) throw new Error(`Unexpected dependency: ${name}`)
    return mocks[name]
  }
  mod._compile(compiled, filename)
  return mod.exports
}

const setting = { 'voice.enabled': false }
const player = { volume: 0.8, isPlay: true, playMusicInfo: { musicInfo: null } }
const calls = []
let emit
let feedback
let permission = 'granted'
let found = Promise.resolve([{ source: 'kw', id: 'song' }])
let searchFailure = false
let searchResults = [{ source: 'kw', id: 'searched' }]
let actionFailure = false
global.app_event = { voiceSearch: (text, type = 'music') => calls.push(['searchUI', text, type]) }
const core = load('../src/core/voice/index.ts', {
  'react-native': {
    AppState: { currentState: 'active', addEventListener() {} }, Platform: { OS: 'android', Version: 32 },
    PermissionsAndroid: {
      PERMISSIONS: { RECORD_AUDIO: 'microphone' }, RESULTS: { GRANTED: 'granted' },
      request: async () => permission, check: async () => true,
    },
  },
  '@/core/common': { updateSetting: update => Object.assign(setting, update), setNavActiveId: id => calls.push(['nav', id]) },
  '@/core/init/deeplink/playerAction': { handlePlayerAction: async action => {
    if (actionFailure) throw new Error('Player unavailable')
    calls.push(['control', action])
  } },
  '@/core/search/music': { search: async text => {
    if (searchFailure) throw new Error('Network unavailable')
    calls.push(['search', text]); return searchResults
  } },
  '@/core/search/songlist': { search: async text => { calls.push(['songlistSearch', text]); return [{ source: 'kw', id: 'playlist' }] } },
  '@/core/search/search': { setSearchText() {}, setSearchType() {}, addHistoryWord: async () => {} },
  '@/utils/data': { saveSearchSetting: async () => {} },
  '@/core/player/tempPlayList': { addTempPlayList: list => calls.push(['queue', list]) },
  '@/core/player/player': { playNext: async () => { calls.push(['next']) } },
  '@/config/constant': { LIST_IDS: { PLAY_LATER: 'later' } },
  '@/store/player/state': { default: player },
  '@/store/setting/state': { default: { setting } },
  '@/plugins/player/utils': { setVolume: async value => { calls.push(['volume', value]) } },
  '@/core/music/utils': { getOtherSource: async () => found },
  '@/utils/tools': { toast: text => calls.push(['toast', text]), assertApiSupport: source => source === 'kw' },
  '@/utils/nativeModules/voice': {
    isVoiceSupported: true, onVoiceState: callback => { emit = callback },
    onVoiceFeedback: callback => { feedback = callback },
    startVoice: async () => { calls.push(['start']) }, stopVoice: async () => { calls.push(['stop']) },
    getVoiceState: async () => ({ status: 'stopped', text: '' }),
    speakVoice: async key => { calls.push(['speak', key]) },
  },
  './commands': load('../src/core/voice/commands.ts'),
})
const settle = async () => { await new Promise(resolve => setImmediate(resolve)) }

async function run() {
  core.initVoice()
  emit({ status: 'recording', text: '' })
  emit({ status: 'recognizing', text: '' })
  assert.deepEqual(calls.filter(call => call[0] === 'volume'), [['volume', 0.12]])
  emit({ status: 'noSpeech', text: '' })
  assert.deepEqual(calls.filter(call => call[0] === 'volume').at(-1), ['volume', 0.8])
  feedback(true)
  assert.deepEqual(calls.filter(call => call[0] === 'volume').at(-1), ['volume', 0.12])
  const volumeCallsDuringSpeech = calls.filter(call => call[0] === 'volume').length
  emit({ status: 'listening', text: '' })
  assert.equal(calls.filter(call => call[0] === 'volume').length, volumeCallsDuringSpeech)
  feedback(false)
  assert.deepEqual(calls.filter(call => call[0] === 'volume').at(-1), ['volume', 0.8])

  await core.enableVoice()
  assert.equal(setting['voice.enabled'], true)
  await core.disableVoice()
  assert.equal(setting['voice.enabled'], false)
  permission = 'denied'
  await assert.rejects(core.enableVoice(), /麦克风/)
  assert.equal(calls.filter(call => call[0] === 'start').length, 1)
  await core.reportVoiceError(new Error('需要允许麦克风权限才能唤醒'))
  assert.deepEqual(calls.at(-1), ['speak', 'permission'])
  await core.reportVoiceError(new Error('请在应用前台开启语音助手'))
  assert.deepEqual(calls.at(-1), ['speak', 'foreground'])
  await core.reportVoiceError(new Error('模型缺失'))
  assert.deepEqual(calls.at(-1), ['speak', 'start_failed'])

  emit({ status: 'result', text: '暂停' })
  await settle()
  assert.deepEqual(calls.at(-1), ['speak', 'no_track'])
  assert.equal(calls.filter(call => call[0] === 'control').length, 0)
  player.playMusicInfo.musicInfo = { id: 'current' }

  emit({ status: 'result', text: '暂停' })
  await settle()
  assert.deepEqual(calls.filter(call => call[0] === 'control').at(-1), ['control', 'pause'])
  assert.deepEqual(calls.at(-1), ['speak', 'pause'])
  for (const [text, key] of [['继续播放', 'play'], ['下一首', 'skipNext'], ['上一首', 'skipPrev'], ['收藏这首歌', 'collect'], ['取消收藏', 'uncollect']]) {
    emit({ status: 'result', text })
    await settle()
    assert.deepEqual(calls.at(-1), ['speak', key])
  }
  emit({ status: 'result', text: '随便说说' })
  await settle()
  assert.deepEqual(calls.at(-1), ['speak', 'unknown_command'])
  actionFailure = true
  emit({ status: 'result', text: '暂停' })
  await settle()
  assert.deepEqual(calls.at(-1), ['speak', 'command_failed'])
  actionFailure = false

  emit({ status: 'result', text: '搜索晴天' })
  await settle()
  assert.deepEqual(calls.filter(call => call[0] === 'searchUI').at(-1), ['searchUI', '晴天', 'music'])
  assert.deepEqual(calls.filter(call => call[0] === 'speak').slice(-2), [['speak', 'searching'], ['speak', 'search_done']])
  searchResults = []
  emit({ status: 'result', text: '搜索不存在的歌曲' })
  await settle()
  assert.deepEqual(calls.at(-1), ['speak', 'search_empty'])
  searchFailure = true
  emit({ status: 'result', text: '搜索晴天' })
  await settle()
  assert.deepEqual(calls.at(-1), ['speak', 'command_failed'])
  searchFailure = false
  player.playMusicInfo.musicInfo = null

  emit({ status: 'result', text: '搜索赵雷的歌单' })
  await settle()
  assert.deepEqual(calls.filter(call => call[0] === 'songlistSearch').at(-1), ['songlistSearch', '赵雷'])
  assert.deepEqual(calls.filter(call => call[0] === 'searchUI').at(-1), ['searchUI', '赵雷', 'songlist'])

  searchResults = [{ source: 'kw', id: 'other-artist', name: '歌', singer: '其他人' }, { source: 'kw', id: 'zhaolei', name: '南方姑娘', singer: '赵雷' }]
  emit({ status: 'result', text: '播放赵雷的歌' })
  await settle()
  assert.equal(calls.filter(call => call[0] === 'queue').at(-1)[1][0].musicInfo.id, 'zhaolei')
  searchResults = []

  emit({ status: 'result', text: '播放晴天' })
  await settle()
  assert.equal(calls.filter(call => call[0] === 'queue').length, 2)
  assert.equal(calls.filter(call => call[0] === 'next').length, 0)
  assert.deepEqual(calls.at(-1), ['speak', 'search_play'])
  player.playMusicInfo.musicInfo = { id: 'current' }
  emit({ status: 'result', text: '播放晴天' })
  await settle()
  assert.equal(calls.filter(call => call[0] === 'next').length, 1)

  searchResults = [{ source: 'kw', id: 'wrong-artist', name: '光年之外', singer: '其他人' }, { source: 'kw', id: 'right-artist', name: '光年之外', singer: '邓紫棋' }]
  const queuedBeforeArtist = calls.filter(call => call[0] === 'queue').length
  emit({ status: 'result', text: '播放邓紫棋唱的光年之外' })
  await settle()
  assert.equal(calls.filter(call => call[0] === 'queue').length, queuedBeforeArtist + 1)
  assert.equal(calls.filter(call => call[0] === 'queue').at(-1)[1][0].musicInfo.id, 'right-artist')
  searchResults = []

  found = Promise.resolve([{ source: 'unsupported', id: 'unplayable' }])
  emit({ status: 'result', text: '播放晴天' })
  await settle()
  assert.equal(calls.filter(call => call[0] === 'queue').length, 4)
  assert.deepEqual(calls.at(-1), ['speak', 'song_not_found'])

  let resolveSearch
  found = new Promise(resolve => { resolveSearch = resolve })
  emit({ status: 'result', text: '播放晴天' })
  await settle()
  emit({ status: 'recording', text: '' })
  resolveSearch([{ source: 'kw', id: 'late-result' }])
  const spokenBeforeCancel = calls.filter(call => call[0] === 'speak').length
  await settle()
  assert.equal(calls.filter(call => call[0] === 'queue').length, 4)
  assert.equal(calls.filter(call => call[0] === 'speak').length, spokenBeforeCancel)
  await core.disableVoice()
  assert.equal(calls.filter(call => call[0] === 'toast').length, 0)
  console.log('Voice integration checks passed: permissions, duck/restore, search, playback and cancellation.')
}
run().catch(error => { console.error(error); process.exitCode = 1 })
