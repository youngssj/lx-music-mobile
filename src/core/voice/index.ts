import { AppState, PermissionsAndroid, Platform } from 'react-native'
import { updateSetting, setNavActiveId } from '@/core/common'
import { handlePlayerAction } from '@/core/init/deeplink/playerAction'
import { search } from '@/core/search/music'
import { search as searchSonglist } from '@/core/search/songlist'
import { setSearchText, setSearchType, addHistoryWord } from '@/core/search/search'
import { saveSearchSetting } from '@/utils/data'
import { addTempPlayList } from '@/core/player/tempPlayList'
import { playNext } from '@/core/player/player'
import { LIST_IDS } from '@/config/constant'
import playerState from '@/store/player/state'
import settingState from '@/store/setting/state'
import { setVolume } from '@/plugins/player/utils'
import { getOtherSource } from '@/core/music/utils'
import { assertApiSupport } from '@/utils/tools'
import { getVoiceState, isVoiceSupported, onVoiceFeedback, onVoiceState, speakVoice, startVoice, stopVoice, type VoiceState } from '@/utils/nativeModules/voice'
import { parseVoiceCommand } from './commands'

let initialized = false
let ducked = false
let feedbackPlaying = false
let capturing = false
let commandGeneration = 0
let lastTranscript = ''
const listeners = new Set<(state: VoiceState) => void>()
let state: VoiceState = { status: 'stopped', text: '' }
const normalizeMusicText = (value: string) => value.replace(/[\s·•()（）【】]/g, '').toLowerCase()
export const subscribeVoice = (listener: (state: VoiceState) => void) => {
  listeners.add(listener)
  listener(state)
  return () => { listeners.delete(listener) }
}
const publish = (next: VoiceState) => {
  state = next
  for (const listener of listeners) listener(state)
}
const restoreVolume = () => {
  if (!ducked) return
  ducked = false
  void setVolume(playerState.volume).catch(() => {})
}

const updateVoiceVolume = () => {
  if ((feedbackPlaying || capturing) && playerState.isPlay) {
    if (ducked) return
    ducked = true
    void setVolume(playerState.volume * 0.15).catch(() => {})
  } else restoreVolume()
}

export const disableVoice = async() => {
  commandGeneration++
  updateSetting({ 'voice.enabled': false })
  capturing = false
  updateVoiceVolume()
  await stopVoice()
  publish({ status: 'stopped', text: lastTranscript })
}

export const enableVoice = async() => {
  if (!isVoiceSupported) throw new Error('离线语音助手仅支持安卓')
  if (AppState.currentState != 'active') throw new Error('请在应用前台开启语音助手')
  const permission = await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.RECORD_AUDIO)
  if (permission != PermissionsAndroid.RESULTS.GRANTED) throw new Error('需要允许麦克风权限才能唤醒')
  if (Number(Platform.Version) >= 33) {
    await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS)
  }
  await startVoice()
  updateSetting({ 'voice.enabled': true })
}

export const reportVoiceError = async(error: Error) => {
  publish({ status: 'error', text: error.message })
  await speakVoice(error.message.includes('麦克风') ? 'permission' : error.message.includes('前台') ? 'foreground' : 'start_failed')
}

const execute = async(transcript: string, generation: number) => {
  const command = parseVoiceCommand(transcript)
  if (!command) {
    await speakVoice('unknown_command')
    return
  }
  if (command.action == 'stopListening') return disableVoice()
  if (command.action != 'search' && command.action != 'searchPlay' && command.action != 'searchSonglist' && command.action != 'playArtist') {
    if (!playerState.playMusicInfo.musicInfo) {
      await speakVoice('no_track')
      return
    }
    await handlePlayerAction(command.action)
    if (generation == commandGeneration) await speakVoice(command.action)
    return
  }
  await speakVoice('searching')
  if (generation != commandGeneration) return
  const query = command.action == 'playArtist' ? command.singer : command.query
  const searchType = command.action == 'searchSonglist' ? 'songlist' : 'music'
  setSearchType(searchType)
  setSearchText(query)
  await saveSearchSetting({ type: searchType, source: 'all' })
  if (generation != commandGeneration) return
  void addHistoryWord(query)
  if (command.action == 'search' || command.action == 'searchSonglist') {
    const results = command.action == 'searchSonglist' ? await searchSonglist(query, 1, 'all') : await search(query, 1, 'all')
    if (generation != commandGeneration) return
    setNavActiveId('nav_search')
    global.app_event.voiceSearch(query, searchType)
    await speakVoice(results.length ? 'search_done' : 'search_empty')
    return
  }
  if (command.action == 'playArtist') {
    const results = await search(command.singer, 1, 'all')
    if (generation != commandGeneration) return
    const singer = normalizeMusicText(command.singer)
    const song = results.find(result => assertApiSupport(result.source) && normalizeMusicText(result.singer).includes(singer))
    if (!song) {
      setNavActiveId('nav_search')
      global.app_event.voiceSearch(query)
      await speakVoice('song_not_found')
      return
    }
    const hasTrack = playerState.playMusicInfo.musicInfo != null
    addTempPlayList([{ listId: LIST_IDS.PLAY_LATER, musicInfo: song, isTop: true }])
    if (hasTrack) await playNext()
    if (generation == commandGeneration) await speakVoice('search_play')
    return
  }
  const musicInfo: LX.Music.MusicInfoLocal = {
    id: `voice_${Date.now()}`,
    name: command.name,
    singer: command.singer,
    source: 'local',
    interval: null,
    meta: { albumName: '', songId: '', filePath: '', ext: '' },
  }
  const results = await search(command.singer ? `${command.singer} ${command.name}` : command.name, 1, 'all')
  if (generation != commandGeneration) return
  const name = normalizeMusicText(command.name)
  const singer = normalizeMusicText(command.singer)
  const matches = (result: LX.Music.MusicInfoOnline) => {
    const resultName = normalizeMusicText(result.name)
    const resultSinger = normalizeMusicText(result.singer)
    return assertApiSupport(result.source) && (resultName == name || resultName.includes(name)) && (!singer || resultSinger.includes(singer))
  }
  let song = results.find(matches)
  if (!song) {
    // The cross-source song matcher helps when search APIs return no exact title/artist pair.
    const fallback = await getOtherSource(musicInfo)
    if (generation != commandGeneration) return
    song = fallback.find(result => assertApiSupport(result.source) && (!singer || matches(result)))
  }
  if (!song && command.singer) {
    // “的” can also be part of the title, e.g. “我的天空”.
    const fallback = await getOtherSource({ ...musicInfo, id: `${musicInfo.id}_title`, name: command.query, singer: '' })
    if (generation != commandGeneration) return
    song = fallback.find(result => assertApiSupport(result.source))
  }
  if (!song) {
    setNavActiveId('nav_search')
    global.app_event.voiceSearch(command.query)
    await speakVoice('song_not_found')
    return
  }
  const hasTrack = playerState.playMusicInfo.musicInfo != null
  addTempPlayList([{ listId: LIST_IDS.PLAY_LATER, musicInfo: song, isTop: true }])
  if (hasTrack) await playNext()
  if (generation == commandGeneration) await speakVoice('search_play')
}

export const initVoice = () => {
  if (initialized || !isVoiceSupported) return
  initialized = true
  AppState.addEventListener('change', status => {
    if (status == 'active') void resumeVoice()
  })
  onVoiceFeedback(playing => {
    feedbackPlaying = playing
    updateVoiceVolume()
  })
  onVoiceState(next => {
    if (next.status == 'recording') commandGeneration++
    capturing = next.status == 'recording' || next.status == 'recognizing'
    updateVoiceVolume()
    if (next.status == 'result') {
      lastTranscript = next.text
      const generation = ++commandGeneration
      void execute(next.text, generation).catch((error: Error) => {
        if (generation != commandGeneration) return
        publish({ status: 'error', text: `语音指令执行失败：${error.message}` })
        void speakVoice('command_failed')
      })
    }
    if (next.status == 'error' || next.status == 'stopped') {
      commandGeneration++
      updateSetting({ 'voice.enabled': false })
    }
    publish({ ...next, text: next.text || lastTranscript })
  })
}

/** Resume only from a visible app, without prompting for permissions on launch. */
export const resumeVoice = async() => {
  if (!isVoiceSupported || !settingState.setting['voice.enabled'] || AppState.currentState != 'active') return
  if (!await PermissionsAndroid.check(PermissionsAndroid.PERMISSIONS.RECORD_AUDIO)) {
    updateSetting({ 'voice.enabled': false })
    await reportVoiceError(new Error('需要允许麦克风权限才能唤醒'))
    return
  }
  const current = await getVoiceState()
  if (current.status == 'stopped' || current.status == 'error') {
    try { await startVoice() } catch (error) {
      updateSetting({ 'voice.enabled': false })
      await reportVoiceError(error as Error)
    }
  }
}
