import { useRef, useImperativeHandle, forwardRef, useState } from 'react'
import { View, TouchableOpacity } from 'react-native'
import ConfirmAlert, { type ConfirmAlertType } from '@/components/common/ConfirmAlert'
import Text from '@/components/common/Text'
import Input, { type InputType } from '@/components/common/Input'
import { createStyle, toast } from '@/utils/tools'
import { useTheme } from '@/store/theme/hook'
import { useI18n } from '@/lang'
import { httpFetch } from '@/utils/request'
import { handleImportScript } from './action'
import { BorderRadius } from '@/theme'

interface UrlInputType {
  setText: (text: string) => void
  getText: () => string
  focus: () => void
}
const UrlInput = forwardRef<UrlInputType, {}>((props, ref) => {
  const theme = useTheme()
  const [text, setText] = useState('')
  const [placeholder, setPlaceholder] = useState('')
  const inputRef = useRef<InputType>(null)

  useImperativeHandle(ref, () => ({
    getText() {
      return text.trim()
    },
    setText(text) {
      setText(text)
      setPlaceholder(global.i18n.t('user_api_btn_import_online_input_tip'))
    },
    focus() {
      inputRef.current?.focus()
    },
  }))

  return (
    <Input
      ref={inputRef}
      placeholder={placeholder}
      value={text}
      onChangeText={setText}
      style={{ ...styles.input, backgroundColor: theme['c-primary-input-background'] }}
    />
  )
})


// 预设源列表（点击快速填入 URL 输入框）
const PRESET_SOURCES: { name: string, url: string }[] = [
  { name: 'lx', url: 'https://ghproxy.net/raw.githubusercontent.com/pdone/lx-music-source/main/lx/latest.js' },
  { name: '六音', url: 'https://ghproxy.net/raw.githubusercontent.com/pdone/lx-music-source/main/sixyin/latest.js' },
  { name: 'huibq', url: 'https://ghproxy.net/raw.githubusercontent.com/pdone/lx-music-source/main/huibq/latest.js' },
  { name: 'flower', url: 'https://ghproxy.net/raw.githubusercontent.com/pdone/lx-music-source/main/flower/latest.js' },
  { name: 'ikun', url: 'https://ghproxy.net/raw.githubusercontent.com/pdone/lx-music-source/main/ikun/latest.js' },
  { name: 'grass', url: 'https://ghproxy.net/raw.githubusercontent.com/pdone/lx-music-source/main/grass/latest.js' },
  { name: '聚合', url: 'https://ghproxy.net/raw.githubusercontent.com/pdone/lx-music-source/main/juhe/latest.js' },
  { name: 'flower-v1.0.0', url: 'https://liuyang-public.oss-cn-beijing.aliyuncs.com/flower-v1.0.0.js' },
  { name: 'keep-alive', url: 'https://fastly.jsdelivr.net/gh/Huibq/keep-alive/render_api.js' },
]


export interface ScriptImportOnlineType {
  show: () => void
}


export default forwardRef<ScriptImportOnlineType, {}>((props, ref) => {
  const t = useI18n()
  const theme = useTheme()
  const alertRef = useRef<ConfirmAlertType>(null)
  const urlInputRef = useRef<UrlInputType>(null)
  const [visible, setVisible] = useState(false)
  const [btn, setBtn] = useState({ disabled: false, text: t('user_api_btn_import_online_input_confirm') })

  const handleShow = () => {
    alertRef.current?.setVisible(true)
    setBtn({ disabled: false, text: t('user_api_btn_import_online_input_confirm') })
    requestAnimationFrame(() => {
      urlInputRef.current?.setText('')
      setTimeout(() => {
        urlInputRef.current?.focus()
      }, 300)
    })
  }
  useImperativeHandle(ref, () => ({
    show() {
      if (visible) handleShow()
      else {
        setVisible(true)
        requestAnimationFrame(() => {
          handleShow()
        })
      }
    },
  }))

  const handlePresetPress = (url: string) => {
    urlInputRef.current?.setText(url)
    urlInputRef.current?.focus()
  }

  const handleImport = async() => {
    let url = urlInputRef.current?.getText() ?? ''
    if (!/^https?:\/\//.test(url)) {
      url = ''
      urlInputRef.current?.setText('')
    }
    if (!url.length) return
    setBtn({ disabled: true, text: t('user_api_btn_import_online_input_loading') })
    let script: string
    try {
      script = await httpFetch(url).promise.then(resp => resp.body) as string
    } catch (err: any) {
      toast(t('user_api_import_failed_tip', { message: err.message }), 'long')
      return
    } finally {
      setBtn({ disabled: false, text: t('user_api_btn_import_online_input_confirm') })
    }
    if (script.length > 9_000_000) {
      toast(t('user_api_import_failed_tip', { message: 'Too large script' }), 'long')
      return
    }
    void handleImportScript(script)

    alertRef.current?.setVisible(false)
  }

  return (
    visible
      ? <ConfirmAlert
          ref={alertRef}
          onConfirm={handleImport}
          disabledConfirm={btn.disabled}
          confirmText={btn.text}
        >
          <View style={styles.reurlContent}>
            <Text style={{ marginBottom: 5 }}>{ t('user_api_btn_import_online')}</Text>
            <UrlInput ref={urlInputRef} />
            <Text style={styles.presetLabel} color={theme['c-font-label']}>{t('user_api_preset_sources')}</Text>
            <View style={styles.presetContainer}>
              {PRESET_SOURCES.map((source) => (
                <TouchableOpacity
                  key={source.url}
                  style={{ ...styles.presetBtn, backgroundColor: theme['c-button-background'] }}
                  onPress={() => handlePresetPress(source.url)}
                >
                  <Text size={13} color={theme['c-button-font']}>{source.name}</Text>
                </TouchableOpacity>
              ))}
            </View>
          </View>
        </ConfirmAlert>
      : null
  )
})


const styles = createStyle({
  reurlContent: {
    flexGrow: 1,
    flexShrink: 1,
    flexDirection: 'column',
  },
  input: {
    flexGrow: 1,
    flexShrink: 1,
    minWidth: 290,
    borderRadius: 4,
    // paddingTop: 2,
    // paddingBottom: 2,
  },
  presetLabel: {
    marginTop: 12,
    marginBottom: 6,
    fontSize: 12,
  },
  presetContainer: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 6,
  },
  presetBtn: {
    paddingTop: 6,
    paddingBottom: 6,
    paddingLeft: 12,
    paddingRight: 12,
    borderRadius: BorderRadius.normal,
  },
})


