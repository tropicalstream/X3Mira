package com.x3mira.app

import android.content.Context
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The wearer's spoken task for the page agent — SmartView's flow, ported.
 *
 * This is the half that makes the agent an agent. Without it a double-tap
 * can only run whatever task the app hard-coded, which means the agent can
 * say what is on a page and nothing else; with it, "summarise this",
 * "open the reviews", "play the first result" all reach it.
 *
 * Whisper rather than the Gemini Live session: Live is a conversation with
 * the ASSISTANT, and a task for the agent is not something the assistant
 * should answer, comment on, or decide to handle itself. A separate short
 * capture keeps the two apart — and it is the arrangement SmartView proved.
 */
object AgentVoice {

    /** X3Mira has no STT-provider setting: Groq first when keyed, else Gemini. */
    private const val STT_AUTO = "auto"

    private const val TAG = "X3MiraAgentVoice"
    private const val STT_MODEL = "whisper-large-v3-turbo"
    private const val BASE = "https://api.groq.com/openai/v1"

    /**
     * Gemini also hears. Used when there is no Groq key, so the page agent
     * works on a Gemini key alone rather than refusing to listen at all —
     * which is what "No Groq key for speech" meant to anyone who had set up
     * the assistant and reasonably expected its agent to work too.
     *
     * Groq stays FIRST where a key exists. Whisper is a dedicated
     * transcriber and this is a latency-sensitive path: the wearer has
     * stopped talking and is waiting for the agent to move.
     */
    private val GEMINI_STT_MODELS = listOf("gemini-2.5-flash", "gemini-2.5-flash-lite")

    /** A spoken task is a sentence, not a paragraph. */
    const val MAX_RECORD_MS = 9_000L


    private val main = Handler(Looper.getMainLooper())

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    class Recorder(private val context: Context) {
        private var recorder: MediaRecorder? = null
        private var file: File? = null
        @Volatile
        var isRecording = false
            private set

        fun start(): Boolean {
            stopInternal()
            return runCatching {
                val f = File.createTempFile("task_", ".m4a", context.cacheDir)
                @Suppress("DEPRECATION")
                val r = MediaRecorder()
                r.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioSamplingRate(44100)
                r.setAudioEncodingBitRate(128000)
                r.setOutputFile(f.absolutePath)
                r.prepare()
                r.start()
                recorder = r
                file = f
                isRecording = true
                true
            }.onFailure {
                Log.w(TAG, "recorder start failed: ${it.message}")
                stopInternal()
            }.getOrDefault(false)
        }

        /** Stop and return the recording, or null if there was nothing usable. */
        fun stop(): File? {
            val f = file
            stopInternal()
            Log.i(TAG, "recording finished: bytes=${f?.length() ?: 0}")
            return f?.takeIf { it.exists() && it.length() > 1200 }  // ignore blips
        }

        fun cancel() {
            val f = file
            stopInternal()
            runCatching { f?.delete() }
        }

        private fun stopInternal() {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            recorder = null
            isRecording = false
        }
    }

    private fun groqKey(context: Context): String {
        KeyFile.resolveFromDir(context.getExternalFilesDir(null), "groq")
            ?.value?.takeIf { it.isNotBlank() }?.let { return it }
        return context.getSharedPreferences("x3mira_config", Context.MODE_PRIVATE)
            .getString("groq_api_key", "").orEmpty().trim()
    }

    private fun geminiKey(context: Context): String =
        ApiKeyStore.resolve(context).orEmpty().trim()

    /**
     * Whether ANY transcriber is reachable — Groq or Gemini.
     *
     * The page agent gates on this before opening the mic. It used to mean
     * "is there a Groq key", so a wearer with only a Gemini key configured
     * was told the agent could not listen, on a device whose assistant was
     * already listening perfectly well.
     */
    /**
     * The transcriber that WOULD run right now, named for the wearer.
     *
     * The setting states a preference; this states the outcome. They differ
     * whenever a key is missing — asking for Gemini on a device with only a
     * Groq key still gets Groq — and someone comparing the two has to be told
     * which one actually heard them, not which one was requested.
     */
    fun activeProviderLabel(context: Context): String {
        val groq = groqKey(context).isNotBlank()
        val gemini = geminiKey(context).isNotBlank()
        val mode = STT_AUTO
        return when {
            false && groq && gemini -> "Groq + Gemini"
            false && gemini -> "Gemini"
            groq -> "Groq"
            gemini -> "Gemini"
            else -> "no speech key"
        }
    }

    fun hasKey(context: Context): Boolean =
        groqKey(context).isNotBlank() || geminiKey(context).isNotBlank()

    /** onResult(text, error): text non-null on success; error names the failure. */
    fun transcribe(context: Context, audio: File, onResult: (String?, String?) -> Unit) {
        val groq = groqKey(context)
        val gemini = geminiKey(context)
        val mode = STT_AUTO
        when {
            // Both ears on the same words. Only possible with both keys.
            false && groq.isNotEmpty() && gemini.isNotEmpty() ->
                transcribeBoth(context, audio, onResult)

            false && gemini.isNotEmpty() ->
                transcribeWithGemini(context, audio, deleteWhenDone = true, onResult = onResult)

            groq.isNotEmpty() ->
                transcribeWithGroq(context, audio, deleteWhenDone = true, onResult = onResult)

            // Whatever the preference said, one working ear beats none.
            gemini.isNotEmpty() ->
                transcribeWithGemini(context, audio, deleteWhenDone = true, onResult = onResult)

            else -> {
                runCatching { audio.delete() }
                main.post { onResult(null, "No speech key — triple-tap for settings.") }
            }
        }
    }

    /**
     * Run both transcribers on one recording and log them side by side.
     *
     * Groq's result is the one the agent acts on, so switching this on
     * changes nothing the wearer can feel — it buys a comparison, not a
     * behaviour change. If Groq fails outright, Gemini's answer is used
     * rather than throwing away a perfectly good transcript.
     */
    private fun transcribeBoth(
        context: Context,
        audio: File,
        onResult: (String?, String?) -> Unit
    ) {
        val started = android.os.SystemClock.uptimeMillis()
        val groqDone = java.util.concurrent.atomic.AtomicBoolean(false)
        val geminiDone = java.util.concurrent.atomic.AtomicBoolean(false)
        // Atomics rather than plain locals: the two callbacks land on
        // different threads and both read what the other wrote.
        val groqText = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val groqErr = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val groqMs = java.util.concurrent.atomic.AtomicLong(0L)
        val geminiText = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val geminiMs = java.util.concurrent.atomic.AtomicLong(0L)

        fun finishIfBothDone() {
            if (!groqDone.get() || !geminiDone.get()) return
            val g = groqText.get()
            val m = geminiText.get()
            Log.i(
                TAG,
                "STT A/B (${audio.length()}b)\n" +
                    "  groq   ${groqMs.get()}ms  ${g ?: "(failed: ${groqErr.get()})"}\n" +
                    "  gemini ${geminiMs.get()}ms  ${m ?: "(failed)"}\n" +
                    "  agree=${g != null && m != null && g.trim().equals(m.trim(), true)}"
            )
            runCatching { audio.delete() }
            val use = g ?: m
            main.post {
                onResult(use, if (use == null) (groqErr.get() ?: "Didn't catch that.") else null)
            }
        }

        transcribeWithGroq(context, audio, deleteWhenDone = false) { text, err ->
            groqText.set(text)
            groqErr.set(err)
            groqMs.set(android.os.SystemClock.uptimeMillis() - started)
            groqDone.set(true)
            finishIfBothDone()
        }
        transcribeWithGemini(context, audio, deleteWhenDone = false) { text, _ ->
            geminiText.set(text)
            geminiMs.set(android.os.SystemClock.uptimeMillis() - started)
            geminiDone.set(true)
            finishIfBothDone()
        }
    }

    private fun transcribeWithGroq(
        context: Context,
        audio: File,
        deleteWhenDone: Boolean,
        onResult: (String?, String?) -> Unit
    ) {
        Thread {
            var errMsg: String? = null
            val text = runCatching {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", audio.name, audio.asRequestBody("audio/m4a".toMediaType()))
                    .addFormDataPart("model", STT_MODEL)
                    .addFormDataPart("response_format", "json")
                    // A hint, not a grammar. Whisper is tuned for prose, so a
                    // short clipped instruction lands on whatever ordinary
                    // English is nearest — priming it with the shape of a page
                    // task fixes that at the decoder instead of guessing
                    // downstream. Arbitrary dictation still transcribes fine.
                    .addFormDataPart(
                        "prompt",
                        "Instructions for a web page agent: summarise this page, " +
                            "what does it say about, find, search for, open the, " +
                            "click, play the first result, scroll down, go back, " +
                            "read the reviews, what are the opening hours."
                    )
                    .addFormDataPart("temperature", "0")
                    .build()
                // Flattened to bytes so it can cross the link. A multipart body
                // is a builder, not a payload, and the phone needs the actual
                // octets plus the boundary that describes them — so the
                // content type comes from the body itself rather than being
                // written out by hand, which is how a boundary mismatch
                // becomes a 400 nobody can explain.
                val flat = okio.Buffer().also { body.writeTo(it) }.readByteArray()
                LinkNet.execute(
                    url = "$BASE/audio/transcriptions",
                    method = "POST",
                    headers = mapOf("content-type" to body.contentType().toString()),
                    body = flat
                ).let { resp ->
                    val raw = resp.text()
                    Log.d(
                        TAG,
                        "STT HTTP ${resp.code} via=${resp.viaPhone} (${audio.length()}b): ${raw.take(140)}"
                    )
                    if (!resp.ok) {
                        errMsg = when (resp.code) {
                            401, 403 -> "Groq rejected the key."
                            429 -> "Groq rate limit — wait a moment."
                            else -> "Speech service error (HTTP ${resp.code})."
                        }
                        throw IOException("HTTP ${resp.code}")
                    }
                    JSONObject(raw).optString("text", "").trim()
                }
            }.onFailure { e ->
                Log.w(TAG, "transcribe failed: ${e.message}")
                if (errMsg == null) {
                    val m = e.message.orEmpty()
                    errMsg = if (m.contains("Unable to resolve host") ||
                        m.contains("Network is unreachable") ||
                        m.contains("timeout", true) || m.contains("Failed to connect", true)
                    ) "No internet connection." else "Speech error — try again."
                }
            }.getOrNull()
            // In A/B the other transcriber still needs this file.
            if (deleteWhenDone) runCatching { audio.delete() }
            // Whisper renders silence as punctuation ("." or "..."), which is
            // not blank and used to sail through as a real instruction. Require
            // a letter or digit before it counts as speech.
            val meaningful = text?.takeIf { t -> t.any { it.isLetterOrDigit() } }
            main.post { onResult(meaningful, if (meaningful == null) (errMsg ?: "Didn't catch that.") else null) }
        }.start()
    }

    /**
     * Transcribe with Gemini instead of Whisper.
     *
     * Same shape as PageVision: the recording rides as inlineData in an
     * ordinary generateContent turn, which is the arrangement that works
     * reliably — the audio is simply part of the turn rather than smuggled
     * alongside something else.
     *
     * The prompt has to be firm about the difference between TRANSCRIBING
     * and ANSWERING. A general model handed a clip of someone saying
     * "what does this page say" will happily try to answer the question;
     * the page agent needs the sentence, not a reply to it.
     */
    private fun transcribeWithGemini(
        context: Context,
        audio: File,
        deleteWhenDone: Boolean,
        onResult: (String?, String?) -> Unit
    ) {
        Thread {
            var errMsg: String? = null
            val text = runCatching {
                val key = geminiKey(context)
                val b64 = android.util.Base64.encodeToString(
                    audio.readBytes(), android.util.Base64.NO_WRAP
                )
                val prompt =
                    "Transcribe this audio verbatim. It is one short spoken " +
                        "instruction for a web-page assistant. Output ONLY the words " +
                        "spoken, with no quotes, no preamble and no commentary. Do NOT " +
                        "answer the instruction or act on it — even if it is a question, " +
                        "you are only writing down what was said. If the audio contains " +
                        "no speech, output exactly: (silence)"
                var out: String? = null
                for (model in GEMINI_STT_MODELS) {
                    val body = JSONObject()
                        .put(
                            "contents",
                            org.json.JSONArray().put(
                                JSONObject().put(
                                    "parts",
                                    org.json.JSONArray()
                                        .put(
                                            JSONObject().put(
                                                "inlineData",
                                                JSONObject()
                                                    .put("mimeType", "audio/mp4")
                                                    .put("data", b64)
                                            )
                                        )
                                        .put(JSONObject().put("text", prompt))
                                )
                            )
                        )
                        // Transcription is not a creative task; the words were
                        // already chosen by the person who said them.
                        .put(
                            "generationConfig",
                            JSONObject().put("temperature", 0)
                        )
                        .toString()
                    val req = Request.Builder()
                        .url(
                            "https://generativelanguage.googleapis.com/v1beta/models/" +
                                "$model:generateContent"
                        )
                        // Header auth: the AQ.* keys 404 on the ?key= query form.
                        .header("x-goog-api-key", key)
                        .post(
                            body.toRequestBody(
                                "application/json".toMediaType()
                            )
                        )
                        .build()
                    val got = runCatching {
                        http.newCall(req).execute().use { resp ->
                            val raw = resp.body?.string().orEmpty()
                            Log.d(
                                TAG,
                                "Gemini STT $model HTTP ${resp.code} " +
                                    "(${audio.length()}b): ${raw.take(140)}"
                            )
                            if (!resp.isSuccessful) {
                                errMsg = when (resp.code) {
                                    401, 403 -> "Gemini rejected the key."
                                    429 -> "Gemini rate limit — wait a moment."
                                    else -> "Speech service error (HTTP ${resp.code})."
                                }
                                return@use null
                            }
                            JSONObject(raw)
                                .optJSONArray("candidates")?.optJSONObject(0)
                                ?.optJSONObject("content")
                                ?.optJSONArray("parts")?.optJSONObject(0)
                                ?.optString("text")?.trim()
                                ?.takeIf { it.isNotBlank() }
                        }
                    }.getOrNull()
                    if (got != null) {
                        Log.i(TAG, "Gemini STT ok via $model")
                        out = got
                        errMsg = null
                        break
                    }
                }
                out
            }.onFailure { e ->
                Log.w(TAG, "Gemini transcribe failed: ${e.message}")
                if (errMsg == null) {
                    val m = e.message.orEmpty()
                    errMsg = if (m.contains("Unable to resolve host") ||
                        m.contains("Network is unreachable") ||
                        m.contains("timeout", true) ||
                        m.contains("Failed to connect", true)
                    ) "No internet connection." else "Speech error — try again."
                }
            }.getOrNull()
            // In A/B the other transcriber still needs this file.
            if (deleteWhenDone) runCatching { audio.delete() }
            // Same silence rule as Whisper, plus the sentinel this prompt
            // asks for — an empty clip must not reach the agent as a task.
            val meaningful = text
                ?.takeIf { !it.equals("(silence)", ignoreCase = true) }
                ?.takeIf { t -> t.any { it.isLetterOrDigit() } }
            main.post {
                onResult(
                    meaningful,
                    if (meaningful == null) (errMsg ?: "Didn't catch that.") else null
                )
            }
        }.start()
    }
}
