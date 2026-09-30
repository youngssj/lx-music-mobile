package cn.toside.music.mobile.voice

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.*
import com.k2fsa.sherpa.onnx.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors

/** One microphone owner; all native model calls and release run on the same worker. */
class VoiceService : Service() {
    companion object {
        const val SAMPLE_RATE = 16000
        const val NOTIFICATION_ID = 7041
        const val CHANNEL = "offline-voice"
        const val STOP = "cn.toside.music.mobile.voice.STOP"
        val MODEL_FILES = listOf(
            "voice/kws/encoder.int8.onnx", "voice/kws/decoder.onnx",
            "voice/kws/joiner.int8.onnx", "voice/kws/tokens.txt", "voice/kws/keywords.txt",
            "voice/asr/model.int8.onnx", "voice/asr/tokens.txt", "voice/silero_vad.onnx"
        )
        @Volatile var status = "stopped"
        @Volatile var lastText = ""
        @Volatile var emitEvent: ((String, String) -> Unit)? = null
        // Serializes model teardown and startup when the user quickly toggles listening.
        private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "OfflineVoice") }
    }

    private val running = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var recorder: AudioRecord? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running.get()) return START_NOT_STICKY
        try {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "离线语音助手", NotificationManager.IMPORTANCE_LOW))
            }
            val notification = notification("正在加载离线模型…")
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else startForeground(NOTIFICATION_ID, notification)
            running.set(true)
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:voice").apply { acquire() }
            executor.execute { if (running.get()) listen() }
        } catch (error: Exception) {
            publish("error", "无法启动后台监听：${error.message}")
            stopSelf()
        }
        // A killed/force-stopped process must be enabled again from a visible activity.
        return START_NOT_STICKY
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent().setClassName(this, "$packageName.MainActivity"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, VoiceService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("离线语音助手").setContentText(message).setContentIntent(open)
            .setOngoing(true).addAction(android.R.drawable.ic_media_pause, "关闭监听", stop).build()
    }

    private fun publish(nextStatus: String, text: String = "") {
        val previous = status
        status = nextStatus
        lastText = text
        val feedback = when (nextStatus) {
            "loading" -> "loading"
            "listening" -> if (previous == "loading") "ready" else null
            "recording" -> "wake_reply"
            "recognizing" -> "recognizing"
            "noSpeech" -> "no_speech"
            "error" -> "error"
            "stopped" -> "stopped"
            else -> null // The command result gets a specific spoken response in JS.
        }
        if (feedback != null) VoiceFeedback.speak(this, feedback)
        main.post {
            emitEvent?.invoke(nextStatus, text)
            if (running.get()) {
                val label = when (nextStatus) {
                    "listening" -> "说“小洛小洛”唤醒"
                    "recording" -> "请说指令，例如：播放周杰伦的晴天"
                    "recognizing" -> "正在离线识别…"
                    else -> text
                }
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(label))
            }
        }
    }

    private fun listen() {
        var spotter: KeywordSpotter? = null
        var recognizer: OfflineRecognizer? = null
        var stream: OnlineStream? = null
        var vad: Vad? = null
        var echo: AcousticEchoCanceler? = null
        var noise: NoiseSuppressor? = null
        try {
            publish("loading")
            spotter = KeywordSpotter(assets, KeywordSpotterConfig(
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "voice/kws/encoder.int8.onnx",
                        decoder = "voice/kws/decoder.onnx", joiner = "voice/kws/joiner.int8.onnx"
                    ), tokens = "voice/kws/tokens.txt", modelType = "zipformer2", numThreads = 1
                ), keywordsFile = "voice/kws/keywords.txt", keywordsThreshold = 0.25f
            ))
            stream = spotter.createStream()
            recognizer = OfflineRecognizer(assets, OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(model = "voice/asr/model.int8.onnx", language = "zh"),
                    tokens = "voice/asr/tokens.txt", numThreads = 2
                )
            ))
            vad = Vad(assets, VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(
                model = "voice/silero_vad.onnx", minSilenceDuration = 0.7f, maxSpeechDuration = 10f
            )))
            if (!running.get()) return
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(bufferSize > 0) { "设备不支持 16kHz 录音" }
            val mic = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(bufferSize * 2, 4096))
            recorder = mic
            check(mic.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
            if (AcousticEchoCanceler.isAvailable()) echo = AcousticEchoCanceler.create(mic.audioSessionId)?.apply { enabled = true }
            if (NoiseSuppressor.isAvailable()) noise = NoiseSuppressor.create(mic.audioSessionId)?.apply { enabled = true }
            mic.startRecording()
            check(mic.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风不可用" }
            val pcm = ShortArray(512)
            var recording = false
            var startedAt = 0L
            var awaitingFeedback = false
            var cooldownUntil = 0L
            publish("listening")
            while (running.get()) {
                val size = mic.read(pcm, 0, pcm.size)
                if (!running.get()) break
                check(size > 0) { "录音中断（$size）" }
                val samples = FloatArray(size) { pcm[it] / 32768f }
                val now = SystemClock.elapsedRealtime()
                // All announcements (including JS command feedback) bypass both models.
                if (VoiceFeedback.isPlaying()) {
                    awaitingFeedback = true
                    continue
                }
                if (awaitingFeedback) {
                    awaitingFeedback = false
                    startedAt = now
                    vad.reset()
                    spotter.reset(stream)
                }
                if (!recording) {
                    if (now < cooldownUntil) continue
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    while (spotter.isReady(stream)) spotter.decode(stream)
                    if (spotter.getResult(stream).keyword.isNotEmpty()) {
                        spotter.reset(stream)
                        vad.reset()
                        recording = true
                        awaitingFeedback = true
                        publish("recording")
                        // Retain tactile feedback when the media volume is muted.
                        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
                        if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)) else vibrator.vibrate(60)
                    }
                } else {
                    vad.acceptWaveform(samples)
                    if (!vad.empty() || now - startedAt >= 12000) {
                        if (vad.empty()) vad.flush()
                        recording = false
                        if (!vad.empty()) {
                            publish("recognizing")
                            val segment = vad.front().samples
                            vad.pop()
                            val utterance = recognizer.createStream()
                            try {
                                utterance.acceptWaveform(segment, SAMPLE_RATE)
                                recognizer.decode(utterance)
                                val text = recognizer.getResult(utterance).text.trim()
                                if (running.get()) publish(if (text.isEmpty()) "noSpeech" else "result", text)
                            } finally { utterance.release() }
                        } else publish("noSpeech", "没有听清，请重新唤醒")
                        vad.reset()
                        spotter.reset(stream)
                        cooldownUntil = SystemClock.elapsedRealtime() + 1000
                        if (running.get()) publish("listening")
                    }
                }
            }
        } catch (error: Throwable) {
            if (running.get()) publish("error", "离线语音错误：${error.message ?: error.javaClass.simpleName}")
        } finally {
            running.set(false)
            try { recorder?.stop() } catch (_: Exception) { }
            recorder?.release()
            recorder = null
            echo?.release()
            noise?.release()
            stream?.release()
            spotter?.release()
            recognizer?.release()
            vad?.release()
            main.post { stopSelf() }
        }
    }

    override fun onDestroy() {
        running.set(false)
        // Keep the error announcement alive after the microphone service stops.
        if (status != "error") VoiceFeedback.cancel()
        try { recorder?.stop() } catch (_: Exception) { }
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        if (status != "error") publish("stopped")
        stopForeground(true)
        super.onDestroy()
    }
}
