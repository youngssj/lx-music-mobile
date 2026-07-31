import { forwardRef, useImperativeHandle, useRef, useState, useEffect } from 'react'
import { AppState, type AppStateStatus } from 'react-native'

import ConfirmAlert, { type ConfirmAlertType } from '@/components/common/ConfirmAlert'

import { toast } from '@/utils/tools'

import { useI18n } from '@/lang'
import { checkDesktopLyricOverlayPermission, hideDesktopLyric, openDesktopLyricOverlayPermissionActivity, showDesktopLyric } from '@/core/desktopLyric'
import { updateSetting } from '@/core/common'

export interface DesktopLyricEnableType {
  setEnabled: (enabled: boolean) => void
}

export default forwardRef<DesktopLyricEnableType, {}>((props, ref) => {
  const t = useI18n()
  const [visible, setVisible] = useState(false)
  // const setIsShowDesktopLyric = useDispatch('common', 'setIsShowDesktopLyric')
  const confirmAlertRef = useRef<ConfirmAlertType>(null)
  // 已跳转到系统"悬浮窗权限"设置页，等待用户返回后补显示桌面歌词
  const pendingPermissionRef = useRef(false)

  useImperativeHandle(ref, () => ({
    setEnabled(enabled) {
      void handleChangeEnableDesktopLyric(enabled)
    },
  }))

  // 从系统授权页返回（app 重新 active）时：若已授权则补显示桌面歌词，否则回退为关闭
  useEffect(() => {
    const subscription = AppState.addEventListener('change', (state: AppStateStatus) => {
      if (state !== 'active' || !pendingPermissionRef.current) return
      pendingPermissionRef.current = false
      void (async() => {
        try {
          await checkDesktopLyricOverlayPermission()
          await showDesktopLyric()
          updateSetting({ 'desktopLyric.enable': true })
        } catch (err) {
          // 仍未授权：回退为关闭
          updateSetting({ 'desktopLyric.enable': false })
        }
      })()
    })
    return () => { subscription.remove() }
  }, [])

  const handleShowModal = () => {
    if (visible) confirmAlertRef.current?.setVisible(true)
    else {
      setVisible(true)
      requestAnimationFrame(() => {
        confirmAlertRef.current?.setVisible(true)
      })
    }
  }
  const handleChangeEnableDesktopLyric = async(isEnable: boolean) => {
    if (isEnable) {
      try {
        await checkDesktopLyricOverlayPermission()
        await showDesktopLyric()
      } catch (err) {
        console.log(err)
        handleShowModal()
        // return false
      }
    } else {
      pendingPermissionRef.current = false
      await hideDesktopLyric()
    }
    // return true
    updateSetting({ 'desktopLyric.enable': isEnable })
  }

  const handleTipsCancel = () => {
    pendingPermissionRef.current = false
    updateSetting({ 'desktopLyric.enable': false })
    toast(t('disagree_tip'), 'long')
  }
  const handleTipsConfirm = () => {
    confirmAlertRef.current?.setVisible(false)
    // 标记待授权：用户从系统设置返回时由 AppState 监听补显示
    pendingPermissionRef.current = true
    void openDesktopLyricOverlayPermissionActivity()
  }

  return (
    visible
      ? (
          <ConfirmAlert
            ref={confirmAlertRef}
            onCancel={handleTipsCancel}
            onConfirm={handleTipsConfirm}
            bgHide={false}
            closeBtn={false}
            cancelText={t('disagree')}
            confirmText={t('agree_go')}
            text={t('setting_lyric_desktop_permission_tip')} />
        )
      : null
  )
})
