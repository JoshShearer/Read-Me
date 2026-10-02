package io.loopstring.readme.playback

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/**
 * Playback's own TextToSpeech instance (AGENTS.md 13). setSpeechRate is the only rate lever
 * (AGENTS.md 9). The engine's voice is chosen by VoicePicker so a network voice is never used
 * (AGENTS.md 5). Construct on the main thread; callbacks other than onReady arrive on the
 * engine's binder thread.
 */
class TtsSpeaker(context: Context, private val callbacks: Callbacks) : Speaker {
  interface Callbacks {
    fun onReady(status: EngineStatus)
    fun onStart(id: String)
    fun onDone(id: String)
    fun onError(id: String)
  }

  enum class EngineStatus(val wire: String) {
    PENDING("pending"), READY("ready"), NO_ENGINE("no-engine"), NO_VOICE("no-voice")
  }

  private val main = Handler(Looper.getMainLooper())

  @Volatile var status = EngineStatus.PENDING
    private set

  // onInit can run synchronously inside this constructor when the engine fails to bind, before
  // `tts` is assigned (SPIKE-05 probe). Posting defers every use of `tts` until it exists.
  private val tts = TextToSpeech(context.applicationContext) { s -> main.post { onInit(s) } }

  private fun onInit(result: Int) {
    status = if (result != TextToSpeech.SUCCESS) EngineStatus.NO_ENGINE else chooseVoice()
    if (status == EngineStatus.READY) {
      tts.setAudioAttributes(ATTRIBUTES)
      tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
        override fun onStart(id: String?) { if (id != null) callbacks.onStart(id) }
        override fun onDone(id: String?) { if (id != null) callbacks.onDone(id) }
        @Deprecated("Deprecated in Java")
        override fun onError(id: String?) { if (id != null) callbacks.onError(id) }
        override fun onError(id: String?, errorCode: Int) { if (id != null) callbacks.onError(id) }
      })
    }
    callbacks.onReady(status)
  }

  private fun chooseVoice(): EngineStatus {
    val voices = runCatching { tts.voices }.getOrNull().orEmpty().toList()
    val default = runCatching { tts.defaultVoice }.getOrNull()
    val language = (default?.locale ?: Locale.getDefault()).language
    val pick = VoicePicker.pick(default?.let(::info), voices.map(::info), language)
      ?: return EngineStatus.NO_VOICE
    val voice = (voices + listOfNotNull(default)).first { it.name == pick.name }
    return if (tts.setVoice(voice) == TextToSpeech.SUCCESS) EngineStatus.READY else EngineStatus.NO_VOICE
  }

  private fun info(v: Voice) = VoiceInfo(
    name = v.name,
    language = v.locale.language,
    networkRequired = v.isNetworkConnectionRequired,
    notInstalled = v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true,
    quality = v.quality,
  )

  override fun speak(id: String, text: String) {
    tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
  }

  override fun stop() {
    tts.stop()
  }

  override fun setRate(rate: Float) {
    tts.setSpeechRate(rate)
  }

  fun shutdown() {
    tts.stop()
    tts.shutdown()
  }

  companion object {
    val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_MEDIA)
      .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
      .build()

    /** The engine's documented input limit, less headroom. */
    fun maxChars(): Int = minOf(Utterances.MAX_CHARS, TextToSpeech.getMaxSpeechInputLength() - 100)
  }
}
