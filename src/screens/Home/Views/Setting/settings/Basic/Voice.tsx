import { memo, useEffect, useState } from 'react'
import { Platform, View } from 'react-native'
import CheckBoxItem from '../../components/CheckBoxItem'
import SubTitle from '../../components/SubTitle'
import Text from '@/components/common/Text'
import { useSettingValue } from '@/store/setting/hook'
import { disableVoice, enableVoice, subscribeVoice } from '@/core/voice'
import { type VoiceState } from '@/utils/nativeModules/voice'
import { toast } from '@/utils/tools'

const labels: Record<VoiceState['status'], string> = {
  stopped: '已关闭',
  loading: '加载离线模型中…',
  listening: '等待“小洛小洛”唤醒',
  recording: '正在听，请说指令',
  recognizing: '正在离线识别',
  result: '识别完成',
  noSpeech: '没有听清，请重新唤醒',
  error: '启动或识别失败',
}
export default memo(() => {
  const enabled = useSettingValue('voice.enabled')
  const [state, setState] = useState<VoiceState>({ status: 'stopped', text: '' })
  const [busy, setBusy] = useState(false)
  useEffect(() => subscribeVoice(setState), [])
  if (Platform.OS != 'android') return null
  const change = (value: boolean) => {
    if (busy) return
    setBusy(true)
    void (value ? enableVoice() : disableVoice()).catch((error: Error) => {
      toast(error.message)
    }).finally(() => { setBusy(false) })
  }
  return (
    <View style={{ marginTop: 15 }}>
      <SubTitle title="离线语音助手">{null}</SubTitle>
      <CheckBoxItem check={enabled} label="开启后台关键词唤醒" onChange={change} />
      <View style={{ paddingLeft: 25, paddingTop: 5 }}>
        <Text size={13}>{busy ? '正在切换…' : labels[state.status]}</Text>
        {state.text ? <Text size={12}>{state.status == 'error' ? state.text : `上次识别：${state.text}`}</Text> : null}
        <Text size={12}>说“小洛小洛”，稍停后说“搜索晴天”“播放周杰伦的晴天”“暂停”“继续播放”“上一首”“下一首”“收藏这首歌”或“关闭语音助手”。</Text>
        <Text size={12}>语音在手机本地处理。支持后台和锁屏监听；常驻通知可关闭。请允许后台运行，应用被强制停止后需重新打开。在线搜歌和播放仍需网络及可用音源。</Text>
      </View>
    </View>
  )
})
