package cn.toside.music.mobile.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import cn.toside.music.mobile.R
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

/** Shared by the service and JS bridge; also works before microphone permission is granted. */
object VoiceFeedback {
    private val clips = mapOf(
        "loading" to R.raw.voice_loading,
        "ready" to R.raw.voice_ready,
        "wake_reply" to R.raw.voice_wake_reply,
        "recognizing" to R.raw.voice_recognizing,
        "no_speech" to R.raw.voice_no_speech,
        "stopped" to R.raw.voice_stopped,
        "error" to R.raw.voice_error,
        "unknown_command" to R.raw.voice_unknown_command,
        "searching" to R.raw.voice_searching,
        "search_done" to R.raw.voice_search_done,
        "search_empty" to R.raw.voice_search_empty,
        "search_play" to R.raw.voice_search_play,
        "song_not_found" to R.raw.voice_song_not_found,
        "play" to R.raw.voice_play,
        "pause" to R.raw.voice_pause,
        "skipNext" to R.raw.voice_skipnext,
        "skipPrev" to R.raw.voice_skipprev,
        "collect" to R.raw.voice_collect,
        "uncollect" to R.raw.voice_uncollect,
        "no_track" to R.raw.voice_no_track,
        "command_failed" to R.raw.voice_command_failed,
        "permission" to R.raw.voice_permission,
        "foreground" to R.raw.voice_foreground,
        "start_failed" to R.raw.voice_start_failed
    )
    private class Request(val context: Context, val resource: Int, val done: () -> Unit)
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Request>()
    private val pending = AtomicInteger(0)
    private var active: Request? = null
    private var player: MediaPlayer? = null
    private var timeout: Runnable? = null
    @Volatile private var tailUntil = 0L
    var emitPlaying: ((Boolean) -> Unit)? = null
    private var announcedPlaying = false

    private fun notifyPlaying(value: Boolean) {
        if (announcedPlaying == value) return
        announcedPlaying = value
        emitPlaying?.invoke(value)
    }

    fun isPlaying() = pending.get() > 0 || SystemClock.elapsedRealtime() < tailUntil

    fun speak(context: Context, key: String, done: () -> Unit = {}) {
        val resource = clips[key] ?: run { done(); return }
        pending.incrementAndGet()
        main.post {
            queue.addLast(Request(context.applicationContext, resource, done))
            playNext()
        }
    }

    private fun playNext() {
        if (active != null || queue.isEmpty()) return
        val request = queue.removeFirst()
        active = request
        notifyPlaying(true)
        try {
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            val output = MediaPlayer.create(request.context, request.resource, attributes, 0)
            if (output == null) { finish(request); return }
            player = output
            output.setOnCompletionListener { finish(request) }
            output.setOnErrorListener { _, _, _ -> finish(request); true }
            val guard = Runnable { finish(request) }
            timeout = guard
            main.postDelayed(guard, 10000)
            output.start()
        } catch (_: Exception) { finish(request) }
    }

    private fun finish(request: Request) {
        if (active !== request) return
        timeout?.let { main.removeCallbacks(it) }
        timeout = null
        player?.release()
        player = null
        active = null
        tailUntil = SystemClock.elapsedRealtime() + 250
        pending.decrementAndGet()
        if (pending.get() == 0) notifyPlaying(false)
        request.done()
        playNext()
    }

    fun cancel() {
        main.post {
            while (!queue.isEmpty()) {
                val request = queue.removeFirst()
                pending.decrementAndGet()
                request.done()
            }
            active?.let { finish(it) }
            if (pending.get() == 0) notifyPlaying(false)
        }
    }
}
