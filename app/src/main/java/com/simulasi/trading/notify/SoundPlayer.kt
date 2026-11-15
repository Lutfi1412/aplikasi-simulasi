package com.simulasi.trading.notify

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlin.math.max

/**
 * Memutar res/raw/notif.mp3 lalu membacakan teks lewat Text-to-Speech.
 *
 * - Sinyal: notif.mp3 1× + ucapan 3×.
 * - Patokan: 3 siklus (notif.mp3 + ucapan) — sama dengan perilaku web.
 *
 * Beberapa kejadian yang datang bersamaan diantre supaya suaranya tidak bertumpuk.
 */
class SoundPlayer(context: Context) {
    private data class Job(val text: String, val cycles: Int, val speechRepeats: Int)

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Job>()

    private var busy = false
    private var token = 0
    private var job: Job? = null
    private var cycle = 0
    private var speechIndex = 0
    private var waitTries = 0
    private var currentUtterId: String? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsFailed = false
    private var player: MediaPlayer? = null

    private val soundAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val speechAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    init {
        main.post {
            tts = TextToSpeech(app) { status -> main.post { onTtsInit(status) } }
        }
    }

    private fun onTtsInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            ttsFailed = true
            return
        }
        t.setAudioAttributes(speechAttrs)
        val result = t.setLanguage(Locale("id", "ID"))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            t.setLanguage(Locale.getDefault())
        }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                main.post {
                    if (utteranceId != null && utteranceId == currentUtterId) speakStep(token)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post {
                    if (utteranceId != null && utteranceId == currentUtterId) speakStep(token)
                }
            }
        })
        ttsReady = true
    }

    fun play(text: String, cycles: Int = 1, speechRepeats: Int = 1) {
        main.post {
            queue.addLast(Job(text, max(1, cycles), max(1, speechRepeats)))
            if (!busy) next()
        }
    }

    fun stopAll() {
        main.post {
            queue.clear()
            token++
            busy = false
            job = null
            currentUtterId = null
            player?.let {
                try {
                    it.stop()
                } catch (_: Exception) {
                }
                it.release()
            }
            player = null
            tts?.stop()
        }
    }

    private fun next() {
        val next = queue.removeFirstOrNull()
        if (next == null) {
            busy = false
            job = null
            return
        }
        job = next
        busy = true
        token++
        cycle = 0
        speechIndex = 0
        waitTries = 0
        playCycle(token)
    }

    private fun playCycle(my: Int) {
        if (token != my) return
        val j = job ?: return
        if (cycle >= j.cycles) {
            busy = false
            job = null
            next()
            return
        }
        waitTries = 0
        val resId = app.resources.getIdentifier("notif", "raw", app.packageName)
        val mp = if (resId != 0) {
            try {
                MediaPlayer.create(app, resId, soundAttrs, AudioManager.AUDIO_SESSION_ID_GENERATE)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        if (mp == null) {
            speakStep(my) // notif.mp3 belum ada/gagal -> langsung ucapan
            return
        }

        player = mp
        mp.setOnCompletionListener {
            it.release()
            if (player === it) player = null
            if (token == my) speakStep(my)
        }
        mp.setOnErrorListener { p, _, _ ->
            p.release()
            if (player === p) player = null
            if (token == my) speakStep(my)
            true
        }
        try {
            mp.start()
        } catch (_: Exception) {
            mp.release()
            if (player === mp) player = null
            speakStep(my)
        }
    }

    private fun speakStep(my: Int) {
        if (token != my) return
        val j = job ?: return
        if (speechIndex >= j.speechRepeats) {
            cycle++
            speechIndex = 0
            playCycle(my)
            return
        }

        val t = tts
        if (t == null || !ttsReady) {
            if (ttsFailed || waitTries >= 40) {
                cycle++
                speechIndex = 0
                playCycle(my)
                return
            }
            waitTries++
            main.postDelayed({ speakStep(my) }, 300)
            return
        }

        speechIndex++
        val id = "sim-$my-$cycle-$speechIndex"
        currentUtterId = id
        if (t.speak(j.text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR) {
            currentUtterId = null
            cycle++
            speechIndex = 0
            playCycle(my)
        }
    }
}
