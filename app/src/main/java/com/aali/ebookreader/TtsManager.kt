package com.aali.ebookreader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Wraps Android TextToSpeech (works offline with the device voices).
 * Speaks a list of text blocks in order and reports both which block is
 * being spoken and, on Android 8 and newer, exactly which characters are
 * being said right now, so the reader can follow along word by word.
 */
class TtsManager(context: Context) {

    interface Listener {
        fun onBlockStarted(index: Int)

        /** Characters [start, end) of block [index] are being spoken now. */
        fun onWordRange(index: Int, start: Int, end: Int) {}

        fun onFinishedAll()
    }

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingAction: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    private var blocks: List<String> = emptyList()
    private var currentIndex = 0
    var listener: Listener? = null
    var speaking = false
        private set

    init {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                try {
                    tts?.language = Locale.US
                } catch (_: Exception) {
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        val idx = utteranceId?.removePrefix("blk-")?.toIntOrNull() ?: return
                        currentIndex = idx
                        main.post { listener?.onBlockStarted(idx) }
                    }

                    override fun onDone(utteranceId: String?) {
                        val idx = utteranceId?.removePrefix("blk-")?.toIntOrNull() ?: return
                        if (idx == blocks.size - 1) {
                            speaking = false
                            main.post { listener?.onFinishedAll() }
                        }
                    }

                    /** Android 8 and newer report the exact words as they are said. */
                    override fun onRangeStart(
                        utteranceId: String?,
                        start: Int,
                        end: Int,
                        frame: Int
                    ) {
                        val idx = utteranceId?.removePrefix("blk-")?.toIntOrNull() ?: return
                        main.post { listener?.onWordRange(idx, start, end) }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                    }
                })
                pendingAction?.let { act -> main.post { act() } }
                pendingAction = null
            }
        }
    }

    fun speakBlocks(texts: List<String>, startIndex: Int) {
        val action = {
            stopInternal()
            blocks = texts
            speaking = true
            val t = tts
            if (t != null) {
                t.setSpeechRate(Prefs.ttsRate(appContext))
                t.setPitch(Prefs.ttsPitch(appContext))
                for (i in startIndex until texts.size) {
                    val chunk = texts[i].take(3900) // TTS input length safety
                    t.speak(chunk, TextToSpeech.QUEUE_ADD, null, "blk-$i")
                }
            }
        }
        if (ready) action() else pendingAction = action
    }

    fun speakOnce(text: String) {
        val action = {
            val t = tts
            if (t != null) {
                t.setSpeechRate(Prefs.ttsRate(appContext))
                t.setPitch(Prefs.ttsPitch(appContext))
                t.speak(text.take(3900), TextToSpeech.QUEUE_FLUSH, null, "one-0")
            }
        }
        if (ready) action() else pendingAction = action
    }

    private fun stopInternal() {
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        speaking = false
    }

    fun stop() {
        stopInternal()
    }

    fun shutdown() {
        stopInternal()
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
    }
}
