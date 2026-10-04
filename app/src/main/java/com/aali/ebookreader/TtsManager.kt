package com.aali.ebookreader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
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

    // the voice follows the language of each passage: Urdu text is read in
    // an Urdu voice when the phone has one, everything else in English
    private var currentLocale: Locale? = null
    private var urduChecked = false
    private var urduLocale: Locale? = null
    private var warnedNoUrdu = false

    private fun findUrduLocale(t: TextToSpeech): Locale? {
        if (urduChecked) return urduLocale
        urduChecked = true
        for (l in listOf(Locale("ur", "PK"), Locale("ur", "IN"), Locale("ur"))) {
            val r = try {
                t.isLanguageAvailable(l)
            } catch (e: Exception) {
                TextToSpeech.LANG_NOT_SUPPORTED
            }
            if (r >= TextToSpeech.LANG_AVAILABLE) {
                urduLocale = l
                break
            }
        }
        return urduLocale
    }

    private fun voiceFor(t: TextToSpeech, text: String) {
        val wantUrdu = Prefs.urduVoice(appContext) && UrduText.isUrdu(text)
        var target = Locale.US
        if (wantUrdu) {
            val ur = findUrduLocale(t)
            if (ur != null) target = ur
            else if (!warnedNoUrdu) {
                warnedNoUrdu = true
                main.post {
                    Toast.makeText(
                        appContext,
                        "This phone has no Urdu voice yet. Settings → Urdu → Get the Urdu voice",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        if (target != currentLocale) {
            try {
                t.language = target
            } catch (_: Exception) {
            }
            currentLocale = target
        }
    }

    init {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                try {
                    tts?.language = Locale.US
                    currentLocale = Locale.US
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
                    voiceFor(t, chunk)
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
                voiceFor(t, text)
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
