package org.bolcr.letmeknow.radio

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Handler
import android.os.Looper
import org.bolcr.letmeknow.shared.Notice
import java.util.Locale

/**
 * Reads a message aloud through the car's speakers, and plays the arrival sound. The voice is Google's Chirp 3 HD,
 * the same as Levite's (free up to a million letters a month, [CloudVoice]); without internet, without the key or if
 * Google says no, the radio's own voice reads.
 */
class Speaker(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val cloud = CloudVoice(app)
    private var player: MediaPlayer? = null
    /** Each reading has a number, so a reading that comes back late after "stop" is not played. */
    @Volatile private var turn = 0
    private var ready = false
    private var pending: String? = null
    /** Told when reading starts (true) and ends (false), for the speaker button's animation. */
    var onSpeaking: (Boolean) -> Unit = {}
    /** Told when the voice really starts sounding (after getting it from Google), so the button stops "loading". */
    var onSound: () -> Unit = {}
    /** Told when a reading ends by itself (not when stopped), so the next thing in line can sound. */
    var onIdle: () -> Unit = {}
    /** From asking Google until the last word: nothing else should start sounding meanwhile. */
    @Volatile private var active = false

    private fun ended() {
        if (!active) return
        active = false
        onSpeaking(false)
        onIdle()
    }

    private val tts: TextToSpeech = TextToSpeech(app) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale("es", "CR")
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { main.post { onSpeaking(true); onSound() } }
                override fun onDone(id: String?) { main.post { ended() } }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { main.post { ended() } }
            })
            pending?.let { local(it) }
            pending = null
        } else {
            // No voice of the radio either: the line must not wait forever.
            pending = null
            main.post { ended() }
        }
    }

    fun read(n: Notice) = say(words(n), if (Settings.female(app, n)) CloudVoice.FEMALE else CloudVoice.MALE)

    fun say(text: String, voice: String = CloudVoice.MALE) {
        stop()
        val mine = ++turn
        active = true
        main.post { onSpeaking(true) }
        Thread {
            val file = cloud.say(text, voice)
            main.post {
                if (mine != turn) return@post
                if (file != null && play(file)) return@post
                local(text)
            }
        }.apply { isDaemon = true }.start()
    }

    private val voiceAttributes = AudioAttributes.Builder()
        // Like the navigation's voice: the music goes down while it reads.
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun play(file: java.io.File): Boolean {
        val p = MediaPlayer()
        return try {
            p.setAudioAttributes(voiceAttributes)
            p.setDataSource(file.path)
            p.setOnCompletionListener { finished(it) }
            // A player that breaks halfway is let go too, so the next reading starts clean.
            p.setOnErrorListener { mp, _, _ -> finished(mp); true }
            p.prepare()
            p.start()
            player = p
            onSound()
            true
        } catch (e: Exception) {
            // Never left open: a radio has only so many players, and once they are used up it goes silent.
            p.release()
            false
        }
    }

    private fun finished(p: MediaPlayer) {
        runCatching { p.release() }
        if (player === p) player = null
        ended()
    }

    private fun local(text: String) {
        if (!ready) { pending = text; return }
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "letmeknow") != TextToSpeech.SUCCESS) ended()
    }

    fun stop() {
        turn++
        active = false
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        pending = null
        if (ready) tts.stop()
        onSpeaking(false)
    }

    val speaking: Boolean get() = active || player != null || (ready && tts.isSpeaking)

    /** The voice is heard now (not only being made). */
    val sounding: Boolean get() = player != null || (ready && tts.isSpeaking)

    /** "Notificación nueva de …": only who (and, with [count], how many are unread), when it is not read whole now. */
    fun announce(n: Notice, count: Int = 0) {
        // Only from two on: with one, the announcement already says it all.
        val many = if (count > 1) " Tiene $count mensajes sin leer." else ""
        say("Notificación nueva de ${n.title.ifBlank { n.app }}.$many", if (Settings.female(app, n)) CloudVoice.FEMALE else CloudVoice.MALE)
    }

    /** The arrival sound: one, reused. A new one each time leaves players open until the radio runs out. */
    private var ring: android.media.Ringtone? = null
    private var tone: MediaPlayer? = null
    private var toneStamp = 0L

    /** The phone's own notification tone when it sent it (so the radio sounds like the phone); else the radio's. */
    fun chime() {
        val file = java.io.File(app.filesDir, TONE_FILE)
        if (file.length() > 0 && phoneTone(file)) return
        try {
            ring?.let { runCatching { it.stop() } }
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return
            ring = ring ?: RingtoneManager.getRingtone(app, uri)
            ring?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                play()
            }
        } catch (_: Exception) {
        }
    }

    private fun phoneTone(file: java.io.File): Boolean = try {
        // Loaded once, and again only when the phone sends a new one.
        if (tone == null || toneStamp != file.lastModified()) {
            tone?.release()
            tone = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                setDataSource(file.path)
                prepare()
            }
            toneStamp = file.lastModified()
        }
        tone!!.let { if (it.isPlaying) it.pause(); it.seekTo(0); it.start() }
        true
    } catch (e: Exception) {
        tone?.release()
        tone = null
        false
    }

    fun shutdown() = tts.shutdown()

    companion object {
        /** Where the phone's notification tone is kept on the radio. */
        const val TONE_FILE = "tono-del-telefono"

        /** What is said: who, and the last message (or that it is a voice message). */
        fun words(n: Notice): String {
            val last = n.lines.lastOrNull()
            val who = n.title.ifBlank { n.app }
            if (n.voice) return "$who mandó un mensaje de voz."
            val sender = last?.sender?.takeIf { it.isNotBlank() && it != n.title }
            val text = last?.text ?: n.text
            return if (sender != null) "$sender, en $who: $text" else "$who: $text"
        }
    }
}

/** Dictation for "Responder": Android's speech recognizer, in Spanish, showing the words while they are said. */
class Dictation(context: Context, private val onWords: (String, Boolean) -> Unit, private val onLevel: (Float) -> Unit,
                private val onFail: () -> Unit) {
    private val app = context.applicationContext
    private var recognizer: SpeechRecognizer? = null

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(app)

    fun start() {
        stop()
        val r = SpeechRecognizer.createSpeechRecognizer(app)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) = onFail()
            override fun onResults(results: Bundle?) {
                onWords(best(results), true)
            }
            override fun onPartialResults(partial: Bundle?) {
                best(partial).takeIf { it.isNotBlank() }?.let { onWords(it, false) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-CR")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
        r.startListening(intent)
    }

    fun stop() {
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    private fun best(b: Bundle?): String =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
}

/**
 * Google's voices in the cloud, as Levite reads its sermons. The key is not in GitHub: it comes from the pastor's
 * computer when the APK is built (TTS_KEY_LEVITE in D:/Levite/claves.properties, see the README). Google takes
 * it only from the apps listed on it by package and SHA-1, so org.bolcr.letmeknow.radio has to be listed there too.
 */
class CloudVoice(private val context: Context) {
    companion object {
        /** Why Google's voice was not used the last time (shown on the radio's screen), or null when it worked. */
        @Volatile var problem: String? = null

        /** The man's voice, Levite's: es-US Chirp 3 HD, Charon. */
        const val MALE = "es-US-Chirp3-HD-Charon"
        /** The woman's voice, of the same kind: Kore. */
        const val FEMALE = "es-US-Chirp3-HD-Kore"
    }

    /**
     * [text] said, as an MP3 in the cache (the same text is not asked twice), or null. A connection that the phone's
     * hotspot dropped is tried once more, fresh.
     */
    fun say(text: String, voice: String = MALE): java.io.File? =
        ask(text, voice) ?: if (problem?.startsWith("Sin internet") == true) ask(text, voice) else null

    private fun ask(text: String, voice: String): java.io.File? {
        val key = BuildConfig.TTS_KEY_LEVITE
        if (key.isBlank()) { problem = "Esta versión no trae la clave de la voz"; return null }
        val name = java.security.MessageDigest.getInstance("SHA-256").digest((voice + "|" + text).toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
        val dir = java.io.File(context.cacheDir, "voz").apply { mkdirs() }
        val file = java.io.File(dir, "$name.mp3")
        if (file.length() > 0) return file
        return try {
            val body = org.json.JSONObject()
                .put("input", org.json.JSONObject().put("text", text))
                .put("voice", org.json.JSONObject().put("languageCode", voice.split('-').take(2).joinToString("-")).put("name", voice))
                .put("audioConfig", org.json.JSONObject().put("audioEncoding", "MP3"))
                .toString().toByteArray()
            val c = java.net.URL("https://texttospeech.googleapis.com/v1/text:synthesize").openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 6_000
            c.readTimeout = 15_000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            // No reused connections: one left half-open by the hotspot made every later reading fail.
            c.setRequestProperty("Connection", "close")
            c.useCaches = false
            c.setRequestProperty("X-Goog-Api-Key", key)
            c.setRequestProperty("X-Android-Package", context.packageName)
            sha1()?.let { c.setRequestProperty("X-Android-Cert", it) }
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            if (code !in 200..299) {
                val said = runCatching { c.errorStream?.use { it.readBytes().decodeToString() } }.getOrNull().orEmpty()
                problem = runCatching { org.json.JSONObject(said).getJSONObject("error").getString("message") }.getOrNull() ?: "HTTP $code"
                return null
            }
            val audio = org.json.JSONObject(c.inputStream.use { it.readBytes().decodeToString() }).getString("audioContent")
            file.writeBytes(android.util.Base64.decode(audio, android.util.Base64.DEFAULT))
            // Keep only the last 200 readings.
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(200)?.forEach { it.delete() }
            problem = null
            file
        } catch (e: Exception) {
            problem = "Sin internet (${e.javaClass.simpleName})"
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun sha1(): String? = try {
        val pm = context.packageManager
        val sig = if (android.os.Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        }
        sig?.let { java.security.MessageDigest.getInstance("SHA-1").digest(it.toByteArray()).joinToString("") { b -> "%02X".format(b) } }
    } catch (e: Exception) {
        null
    }
}
