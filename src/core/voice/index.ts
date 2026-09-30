import { AppState, PermissionsAndroid, Platform } from 'react-native'
import { updateSetting, setNavActiveId } from '@/core/common'
import { handlePlayerAction } from '@/core/init/deeplink/playerAction'
import { search } from '@/core/search/music'
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
  if (command.action != 'search' && command.action != 'searchPlay') {
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
  setSearchType('music')
  setSearchText(command.query)
  await saveSearchSetting({ type: 'music', source: 'all' })
  if (generation != commandGeneration) return
  void addHistoryWord(command.query)
  if (command.action == 'search') {
    const results = await search(command.query, 1, 'all')
    if (generation != commandGeneration) return
    setNavActiveId('nav_search')
    global.app_event.voiceSearch(command.query)
    await speakVoice(results.length ? 'search_done' : 'search_empty')
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
  const results = await getOtherSource(musicInfo)
  if (generation != commandGeneration) return
  let song = results.find(result => assertApiSupport(result.source))
  if (!song && command.singer) {
    // “的” can belong to the title rather than separate artist and song.
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
