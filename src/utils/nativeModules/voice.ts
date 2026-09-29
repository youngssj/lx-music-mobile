import { NativeEventEmitter, NativeModules, Platform, type EmitterSubscription, type NativeModule } from 'react-native'

export interface VoiceState {
  status: 'stopped' | 'loading' | 'listening' | 'recording' | 'recognizing' | 'result' | 'noSpeech' | 'error'
  text: string
}
interface VoiceNative extends NativeModule {
  start: () => Promise<void>
  stop: () => Promise<void>
  getState: () => Promise<VoiceState>
}
const native = NativeModules.VoiceModule as VoiceNative | undefined
export const isVoiceSupported = Platform.OS == 'android' && native != null
export const startVoice = async(): Promise<void> => {
  if (!isVoiceSupported || !native) throw new Error('离线语音助手仅支持安卓')
  await native.start()
}
export const stopVoice = async(): Promise<void> => { await native?.stop() }
export const getVoiceState = async(): Promise<VoiceState> => native?.getState() ?? { status: 'stopped', text: '' }
export const onVoiceState = (listener: (state: VoiceState) => void): EmitterSubscription | undefined => {
  if (!isVoiceSupported) return
  return new NativeEventEmitter(native).addListener('voice-state', listener)
}
