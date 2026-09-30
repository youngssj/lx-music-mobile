package cn.toside.music.mobile.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.facebook.react.common.LifecycleState

class VoiceModule(private val context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
    override fun getName() = "VoiceModule"

    override fun initialize() {
        super.initialize()
        VoiceFeedback.emitPlaying = { playing ->
            if (context.hasActiveReactInstance()) {
                context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                    .emit("voice-feedback", playing)
            }
        }
        VoiceService.emitEvent = { status, text ->
            if (context.hasActiveReactInstance()) {
                val event = Arguments.createMap()
                event.putString("status", status)
                event.putString("text", text)
                context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                    .emit("voice-state", event)
            }
        }
    }

    @ReactMethod
    fun start(promise: Promise) {
        if (currentActivity == null || context.lifecycleState != LifecycleState.RESUMED) {
            promise.reject("VOICE_FOREGROUND", "请在应用前台开启语音助手")
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            promise.reject("VOICE_PERMISSION", "需要麦克风权限")
            return
        }
        try {
            for (path in VoiceService.MODEL_FILES) context.assets.open(path).use { }
            val intent = Intent(context, VoiceService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            promise.resolve(null)
        } catch (error: Exception) {
            promise.reject("VOICE_START", "离线模型未准备好或无法启动：${error.message}", error)
        }
    }

    @ReactMethod
    fun stop(promise: Promise) {
        context.stopService(Intent(context, VoiceService::class.java))
        promise.resolve(null)
    }

    @ReactMethod
    fun speak(key: String, promise: Promise) {
        VoiceFeedback.speak(context, key) { promise.resolve(null) }
    }

    @ReactMethod
    fun getState(promise: Promise) {
        val event = Arguments.createMap()
        event.putString("status", VoiceService.status)
        event.putString("text", VoiceService.lastText)
        promise.resolve(event)
    }

    @ReactMethod fun addListener(name: String) { }
    @ReactMethod fun removeListeners(count: Double) { }

    override fun invalidate() {
        context.stopService(Intent(context, VoiceService::class.java))
        VoiceFeedback.cancel()
        VoiceFeedback.emitPlaying = null
        VoiceService.emitEvent = null
        super.invalidate()
    }
}
