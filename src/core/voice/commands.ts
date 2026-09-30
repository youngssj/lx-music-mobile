type ControlAction = 'play' | 'pause' | 'skipNext' | 'skipPrev' | 'collect' | 'uncollect' | 'stopListening'
export type VoiceCommand =
  | { action: ControlAction }
  | { action: 'searchSonglist', query: string }
  | { action: 'playArtist', singer: string }
  | { action: 'search' | 'searchPlay', query: string, name: string, singer: string }

/** Exact control phrases avoid treating song titles containing “暂停” as controls. */
export const parseVoiceCommand = (transcript: string): VoiceCommand | null => {
  const text = transcript.replace(/<\|[^>]*\|>/g, '').replace(/[\s，。！？、,.!?]/g, '')
    .replace(/^(小洛小洛|小落小落)/, '').replace(/^(请帮我|帮我|请)/, '')
  const controls: Record<string, ControlAction> = {
    暂停: 'pause',
    暂停播放: 'pause',
    停止播放: 'pause',
    播放: 'play',
    继续: 'play',
    继续播放: 'play',
    恢复播放: 'play',
    下一首: 'skipNext',
    下一曲: 'skipNext',
    切歌: 'skipNext',
    上一首: 'skipPrev',
    上一曲: 'skipPrev',
    收藏: 'collect',
    收藏这首歌: 'collect',
    收藏歌曲: 'collect',
    取消收藏: 'uncollect',
    取消收藏这首歌: 'uncollect',
    关闭语音助手: 'stopListening',
    关闭监听: 'stopListening',
  }
  const control = Object.prototype.hasOwnProperty.call(controls, text) ? controls[text] : undefined
  if (control) return { action: control }
  const list = /^(?:搜索|搜一下|搜|查找|找)(?:一下)?(.+?)(?:的)?歌单$/.exec(text)
  if (list?.[1]) return { action: 'searchSonglist', query: list[1] }
  const searchArtist = /^(?:搜索|搜一下|搜|查找|找)(?:一下)?(.{2,}?)(?:的歌曲|的歌|唱的歌)$/.exec(text)
  if (searchArtist?.[1]) return { action: 'search', query: searchArtist[1], name: searchArtist[1], singer: '' }
  const artist = /^(?:播放|放点|来点|我想听|听听|听一下|放一首|来一首|放首|来首)(.{2,}?)(?:的歌曲|的歌|唱的歌|的音乐)$/.exec(text)
  if (artist?.[1]) return { action: 'playArtist', singer: artist[1] }
  const match = /^(搜索歌曲|搜索|搜歌|搜一下|查找|找歌|播放歌曲|播放|我想听|听一下|放一首|来一首)(.+)$/.exec(text)
  if (!match) return null
  const query = match[2]
  // A single-character prefix is usually part of a title, e.g. “我的天空”“夜的第七章”.
  const singerSong = /^(.{2,}?)(?:唱的|演唱的|的)(.+)$/.exec(query)
  const action = /^(搜索歌曲|搜索|搜歌|搜一下|查找)$/.test(match[1]) ? 'search' : 'searchPlay'
  return { action, query, name: singerSong?.[2] ?? query, singer: singerSong?.[1] ?? '' }
}
