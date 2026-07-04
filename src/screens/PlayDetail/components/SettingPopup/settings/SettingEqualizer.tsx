import { useState } from 'react'
import { View } from 'react-native'
import { useTheme } from '@/store/theme/hook'
import Text from '@/components/common/Text'
import { useSettingValue } from '@/store/setting/hook'
import Slider, { type SliderProps } from '@/components/common/Slider'
import { updateSetting } from '@/core/common'
import { useI18n } from '@/lang'
import { createStyle } from '@/utils/tools'
import { setEqualizerEnabled, setEqualizerBandLevel } from '@/plugins/player'
import settingState from '@/store/setting/state'
import CheckBox from '@/components/common/CheckBox'

// 自建 20 段均衡器频段标签：20 31 45 63 80 125 250 500 800 1k 1.25k 2k 3.15k 4k 6.3k 8k 10k 12.5k 16k 20k Hz
const BAND_LABELS = ['20Hz', '31Hz', '45Hz', '63Hz', '80Hz', '125Hz', '250Hz', '500Hz', '800Hz', '1kHz', '1.25kHz', '2kHz', '3.15kHz', '4kHz', '6.3kHz', '8kHz', '10kHz', '12.5kHz', '16kHz', '20kHz']
// 均衡器增益范围：-15dB ~ +15dB，单位：毫贝（millibel），1dB = 100mb
const MIN_DB = -15
const MAX_DB = 15

export default () => {
  const theme = useTheme()
  const isEnabled = useSettingValue('player.isEqualizerEnabled')
  const bands = useSettingValue('player.equalizerBands')
  const [resetKey, setResetKey] = useState(0)
  const t = useI18n()

  const handleToggle = (checked: boolean) => {
    updateSetting({ 'player.isEqualizerEnabled': checked })
    void setEqualizerEnabled(checked)
  }

  const handleBandChange = (bandIndex: number): SliderProps['onValueChange'] => value => {
    value = Math.round(value)
    void setEqualizerBandLevel(bandIndex, value * 100)
  }

  const handleBandComplete = (bandIndex: number): SliderProps['onSlidingComplete'] => value => {
    value = Math.round(value)
    // 以 BAND_LABELS 长度为准归一化，兼容旧版（5段）持久化数据，避免产生稀疏数组
    const cur = settingState.setting['player.equalizerBands']
    const newBands = BAND_LABELS.map((_, i) => i === bandIndex ? value : (Array.isArray(cur) && cur.length > i ? cur[i] : 0))
    updateSetting({ 'player.equalizerBands': newBands })
  }

  const handleReset = () => {
    const zeroBands = BAND_LABELS.map(() => 0)
    updateSetting({ 'player.equalizerBands': zeroBands })
    zeroBands.forEach((_, i) => {
      void setEqualizerBandLevel(i, 0)
    })
    // 切换 key 强制 Slider 重新挂载，确保 Android 端滑块视觉位置归零
    setResetKey(key => key + 1)
  }

  return (
    <View style={styles.container}>
      <Text>{t('play_detail_setting_equalizer')}</Text>
      <View style={styles.toggleRow}>
        <CheckBox check={isEnabled} label={t('play_detail_setting_equalizer_enable')} onChange={handleToggle} />
      </View>
      {isEnabled && (
        <View style={styles.bandsContainer}>
          {BAND_LABELS.map((label, index) => {
            const value = Array.isArray(bands) && bands.length > index ? bands[index] : 0
            return (
              <View key={index} style={styles.bandRow}>
                <Text style={styles.bandLabel} color={theme['c-font-label']}>{label}</Text>
                <Slider
                  key={`${index}-${resetKey}`}
                  minimumValue={MIN_DB}
                  maximumValue={MAX_DB}
                  onValueChange={handleBandChange(index)}
                  onSlidingComplete={handleBandComplete(index)}
                  step={1}
                  value={value}
                />
                <Text style={styles.bandValue} color={theme['c-font-label']}>{value > 0 ? `+${value}` : `${value}`}</Text>
              </View>
            )
          })}
          <View style={styles.resetRow}>
            <Text style={styles.resetBtn} color={theme['c-primary']} onPress={handleReset}>{t('play_detail_setting_playback_rate_reset')}</Text>
          </View>
        </View>
      )}
    </View>
  )
}

const styles = createStyle({
  container: {
    paddingTop: 5,
    paddingLeft: 15,
    paddingRight: 15,
    paddingBottom: 15,
    alignItems: 'flex-start',
  },
  toggleRow: {
    marginTop: 5,
  },
  bandsContainer: {
    width: '100%',
    marginTop: 8,
  },
  bandRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 4,
  },
  bandLabel: {
    width: 60,
    fontSize: 12,
  },
  bandValue: {
    width: 40,
    textAlign: 'right',
    fontSize: 12,
  },
  resetRow: {
    marginTop: 6,
    alignItems: 'flex-end',
  },
  resetBtn: {
    paddingVertical: 4,
    paddingHorizontal: 8,
    fontSize: 13,
  },
})
