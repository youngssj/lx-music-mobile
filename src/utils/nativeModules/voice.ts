import { NativeEventEmitter, NativeModules, Platform, type EmitterSubscription, type NativeModule } from 'react-native'
import type prompts from '@/resources/voice/prompts.json'

export type VoicePrompt = keyof typeof prompts

export interface VoiceState {
  status: 'stopped' | 'loading' | 'listening' | 'recording' | 'recognizing' | 'result' | 'noSpeech' | 'error'
  text: string
}
interface VoiceNative extends NativeModule {
  start: () => Promise<void>
  stop: () => Promise<void>
  getState: () => Promise<VoiceState>
  speak: (key: VoicePrompt) => Promise<void>
}
const native = NativeModules.VoiceModule as VoiceNative | undefined
export const isVoiceSupported = Platform.OS == 'android' && native != null
export const startVoice = async(): Promise<void> => {
  if (!isVoiceSupported || !native) throw new Error('离线语音助手仅支持安卓')
  await native.start()
}
export const stopVoice = async(): Promise<void> => { await native?.stop() }
export const speakVoice = async(key: VoicePrompt): Promise<void> => {
  // Playback failure must not prevent the requested music operation.
  try { await native?.speak(key) } catch {}
}
export const getVoiceState = async(): Promise<VoiceState> => native?.getState() ?? { status: 'stopped', text: '' }
export const onVoiceState = (listener: (state: VoiceState) => void): EmitterSubscription | undefined => {
  if (!isVoiceSupported) return
  return new NativeEventEmitter(native).addListener('voice-state', listener)
}
export const onVoiceFeedback = (listener: (playing: boolean) => void): EmitterSubscription | undefined => {
  if (!isVoiceSupported) return
  return new NativeEventEmitter(native).addListener('voice-feedback', listener)
}
