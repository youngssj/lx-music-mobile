import { init as initLyricPlayer, toggleTranslation, toggleRoma, play, pause, stop, setLyric, setPlaybackRate } from '@/core/lyric'
import { updateSetting, saveSettingNow } from '@/core/common'
import { onDesktopLyricPositionChange, onDesktopLyricWidthChange, onDesktopLyricMaxLineNumChange, showDesktopLyric, hideDesktopLyric, onLyricLinePlay, showRemoteLyric, onDesktopLyricControl, onDesktopLyricClose, setDesktopLyricPlaying } from '@/core/desktopLyric'
import { handlePlayerAction } from '@/core/init/deeplink/playerAction'
import playerState from '@/store/player/state'
import { updateNowPlayingTitles } from '@/plugins/player/utils'
import { setLastLyric } from '@/core/player/playInfo'
import { state } from '@/plugins/player/playList'

const updateRemoteLyric = async(lrc?: string) => {
  setLastLyric(lrc)
  if (lrc == null) {
    void updateNowPlayingTitles((state.prevDuration || 0) * 1000, playerState.musicInfo.name, playerState.musicInfo.singer ?? '', playerState.musicInfo.album ?? '')
  } else {
    void updateNowPlayingTitles((state.prevDuration || 0) * 1000, lrc, `${playerState.musicInfo.name}${playerState.musicInfo.singer ? ` - ${playerState.musicInfo.singer}` : ''}`, playerState.musicInfo.album ?? '')
  }
}

export default async(setting: LX.AppSetting) => {
  await initLyricPlayer()
  await Promise.all([
    setPlaybackRate(setting['player.playbackRate']),
    toggleTranslation(setting['player.isShowLyricTranslation']),
    toggleRoma(setting['player.isShowLyricRoma']),
  ])

  if (setting['desktopLyric.enable']) {
    showDesktopLyric().catch(() => {
      updateSetting({ 'desktopLyric.enable': false })
    })
  }
  if (setting['player.isShowBluetoothLyric']) {
    showRemoteLyric(true).catch(() => {
      updateSetting({ 'player.isShowBluetoothLyric': false })
    })
  }
  onDesktopLyricPositionChange(position => {
    updateSetting({
      'desktopLyric.position.x': position.x,
      'desktopLyric.position.y': position.y,
    })
    saveSettingNow()
  })
  onDesktopLyricWidthChange(width => {
    updateSetting({ 'desktopLyric.width': width })
    saveSettingNow()
  })
  onDesktopLyricMaxLineNumChange(maxLineNum => {
    updateSetting({ 'desktopLyric.maxLineNum': maxLineNum })
    saveSettingNow()
  })
  // 浮窗控制栏按钮 → 控制播放
  onDesktopLyricControl(action => {
    if (action === 'prev') void handlePlayerAction('skipPrev')
    else if (action === 'next') void handlePlayerAction('skipNext')
    else if (action === 'playPause') void handlePlayerAction('togglePlay')
  })
  // 浮窗关闭（已二次确认）→ 关闭并禁用，持久化
  onDesktopLyricClose(() => {
    void hideDesktopLyric()
    updateSetting({ 'desktopLyric.enable': false })
    saveSettingNow()
  })
  onLyricLinePlay(({ text, extendedLyrics }) => {
    if (!text && !state.isPlaying) {
      void updateRemoteLyric()
    } else {
      void updateRemoteLyric(text)
    }
  })


  global.app_event.on('play', play)
  global.app_event.on('pause', pause)
  global.app_event.on('stop', stop)
  global.app_event.on('error', pause)
  global.app_event.on('musicToggled', stop)
  global.app_event.on('lyricUpdated', setLyric)

  // 播放状态变化时同步浮窗控制栏的播放/暂停按钮图标
  global.app_event.on('play', () => { void setDesktopLyricPlaying(true) })
  global.app_event.on('pause', () => { void setDesktopLyricPlaying(false) })
  global.app_event.on('stop', () => { void setDesktopLyricPlaying(false) })
}
