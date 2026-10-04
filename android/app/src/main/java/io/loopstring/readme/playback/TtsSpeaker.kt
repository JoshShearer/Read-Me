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
class TtsSpeaker(context: Context, private val callbacks: Callbacks, private val preferredVoice: String? = null) : Speaker {
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
  // Null when Android refused the bind (REA-32): onInit then gets ERROR and status is NO_ENGINE.
  private val tts: TextToSpeech? =
    TtsOpen.open(main, { TextToSpeech(context.applicationContext, { s -> main.post { onInit(s) } }, TtsOpen.engine(context)) }) { onInit(it) }

  private fun onInit(result: Int) {
    val tts = tts
    status = if (result != TextToSpeech.SUCCESS || tts == null) EngineStatus.NO_ENGINE else chooseVoice(tts)
    if (status == EngineStatus.READY && tts != null) {
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

  private fun chooseVoice(tts: TextToSpeech): EngineStatus {
    val voice = chooseVoice(tts, preferredVoice) ?: return EngineStatus.NO_VOICE
    return if (tts.setVoice(voice) == TextToSpeech.SUCCESS) EngineStatus.READY else EngineStatus.NO_VOICE
  }

  override fun speak(id: String, text: String): Boolean =
    tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.SUCCESS

  override fun isSpeaking(): Boolean = runCatching { tts?.isSpeaking == true }.getOrDefault(false)

  override fun stop() {
    tts?.stop()
  }

  override fun setRate(rate: Float) {
    tts?.setSpeechRate(rate)
  }

  fun shutdown() {
    tts?.stop()
    tts?.shutdown()
  }

  companion object {
    fun info(v: Voice) = VoiceInfo(
      name = v.name,
      language = v.locale.language,
      networkRequired = v.isNetworkConnectionRequired,
      notInstalled = v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true,
      quality = v.quality,
      tag = v.locale.toLanguageTag(),
    )

    /** R-M06, AGENTS.md 5: VoicePicker's choice among this engine's voices; null means none is offline. */
    fun chooseVoice(tts: TextToSpeech, preferred: String?): Voice? {
      val voices = runCatching { tts.voices }.getOrNull().orEmpty().toList()
      val default = runCatching { tts.defaultVoice }.getOrNull()
      val language = (default?.locale ?: Locale.getDefault()).language
      val pick = VoicePicker.pick(default?.let { info(it) }, voices.map { info(it) }, language, preferred) ?: return null
      return (voices + listOfNotNull(default)).first { it.name == pick.name }
    }

    val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_MEDIA)
      .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
      .build()

    /** The engine's documented input limit, less headroom. */
    fun maxChars(): Int = minOf(Utterances.MAX_CHARS, TextToSpeech.getMaxSpeechInputLength() - 100)
  }
}
