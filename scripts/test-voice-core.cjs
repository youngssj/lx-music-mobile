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
let permission = 'granted'
let found = Promise.resolve([{ source: 'kw', id: 'song' }])
global.app_event = { voiceSearch: text => calls.push(['searchUI', text]) }
const core = load('../src/core/voice/index.ts', {
  'react-native': {
    AppState: { currentState: 'active', addEventListener() {} }, Platform: { OS: 'android', Version: 32 },
    PermissionsAndroid: {
      PERMISSIONS: { RECORD_AUDIO: 'microphone' }, RESULTS: { GRANTED: 'granted' },
      request: async () => permission, check: async () => true,
    },
  },
  '@/core/common': { updateSetting: update => Object.assign(setting, update), setNavActiveId: id => calls.push(['nav', id]) },
  '@/core/init/deeplink/playerAction': { handlePlayerAction: async action => calls.push(['control', action]) },
  '@/core/search/music': { search: async text => { calls.push(['search', text]); return [] } },
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
    startVoice: async () => { calls.push(['start']) }, stopVoice: async () => { calls.push(['stop']) },
    getVoiceState: async () => ({ status: 'stopped', text: '' }),
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

  await core.enableVoice()
  assert.equal(setting['voice.enabled'], true)
  await core.disableVoice()
  assert.equal(setting['voice.enabled'], false)
  permission = 'denied'
  await assert.rejects(core.enableVoice(), /麦克风/)
  assert.equal(calls.filter(call => call[0] === 'start').length, 1)

  emit({ status: 'result', text: '暂停' })
  await settle()
  assert.deepEqual(calls.filter(call => call[0] === 'control').at(-1), ['control', 'pause'])

  emit({ status: 'result', text: '搜索晴天' })
  await settle()
  assert.deepEqual(calls.filter(call => call[0] === 'searchUI').at(-1), ['searchUI', '晴天'])

  emit({ status: 'result', text: '播放晴天' })
  await settle()
  assert.equal(calls.filter(call => call[0] === 'queue').length, 1)
  assert.equal(calls.filter(call => call[0] === 'next').length, 0)
  player.playMusicInfo.musicInfo = { id: 'current' }
  emit({ status: 'result', text: '播放晴天' })
  await settle()
  assert.equal(calls.filter(call => call[0] === 'next').length, 1)

  found = Promise.resolve([{ source: 'unsupported', id: 'unplayable' }])
  emit({ status: 'result', text: '播放晴天' })
  await settle()
  assert.equal(calls.filter(call => call[0] === 'queue').length, 2)

  let resolveSearch
  found = new Promise(resolve => { resolveSearch = resolve })
  emit({ status: 'result', text: '播放晴天' })
  await settle()
  await core.disableVoice()
  resolveSearch([{ source: 'kw', id: 'late-result' }])
  await settle()
  assert.equal(calls.filter(call => call[0] === 'queue').length, 2)
  console.log('Voice integration checks passed: permissions, duck/restore, search, playback and cancellation.')
}
run().catch(error => { console.error(error); process.exitCode = 1 })
