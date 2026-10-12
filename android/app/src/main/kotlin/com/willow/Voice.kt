package com.willow

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Willow's calm narrator (voice.js in the prototype): slow Mandarin, one sentence at a time.
 * It speaks on the accessibility audio stream, because Willow mutes the media stream that the
 * scam video plays on, and the explanation must still be heard.
 */
class Voice(ctx: Context) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(ctx.applicationContext, this)
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private var pending: ((Boolean) -> Unit)? = null

    var ready = false
        private set
    var speaking = false
        private set

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val lang = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) return
        tts.setSpeechRate(0.85f)
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {}
            override fun onDone(id: String) = finished(id)
            @Deprecated("Deprecated in Java")
            override fun onError(id: String) = finished(id)
        })
        ready = true
    }

    /**
     * Speaks [text], then calls [done] on the main thread: true if it was spoken, false if there
     * is no voice (the caller then paces by reading time). Not called if stopped or superseded.
     */
    fun say(text: String, done: (spoke: Boolean) -> Unit) {
        stop()
        if (!ready || text.isBlank()) { done(false); return }
        val gen = generation
        pending = done
        speaking = true
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "w$gen")
        // Like the prototype: some engines never report the end, so don't let the flow hang.
        main.postDelayed({ finish(gen) }, 2500L + text.length * 380L)
    }

    fun stop() {
        generation++
        pending = null
        speaking = false
        main.removeCallbacksAndMessages(null)
        if (ready) tts.stop()
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    private fun finished(id: String) {
        val gen = id.removePrefix("w").toIntOrNull() ?: return
        main.post { finish(gen) }
    }

    private fun finish(gen: Int) {
        if (gen != generation || !speaking) return
        speaking = false
        val cb = pending
        pending = null
        cb?.invoke(true)
    }
}
