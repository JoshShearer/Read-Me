package io.loopstring.readme.playback

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.File
import java.util.Locale

/**
 * Playback's own TextToSpeech instance (AGENTS.md 13). setSpeechRate is the only rate lever
 * (AGENTS.md 9). The engine's voice is chosen by VoicePicker so a network voice is never used
 * (AGENTS.md 5). Construct on the main thread; callbacks other than onReady arrive on the
 * engine's binder thread.
 *
 * REA-31 (ADR 0012): it never speaks. Each sentence is synthesized to a file that Read Me plays
 * on its own output, because audio the engine plays is the engine's: Android then gave media
 * keys to the engine's session (Supertonic's), not Read Me's.
 */
class TtsSpeaker(context: Context, private val callbacks: Callbacks, private val preferredVoice: String? = null) : SentenceSynth {
  interface Callbacks {
    fun onReady(status: EngineStatus)
    fun onSynthDone(id: String)
    /** [lost]: ERROR_SERVICE, the engine's process is gone. */
    fun onSynthError(id: String, lost: Boolean)
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
      tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
        override fun onStart(id: String?) {}
        override fun onDone(id: String?) { if (id != null) callbacks.onSynthDone(id) }
        @Deprecated("Deprecated in Java")
        override fun onError(id: String?) { if (id != null) callbacks.onSynthError(id, lost = false) }
        override fun onError(id: String?, errorCode: Int) {
          if (id != null) callbacks.onSynthError(id, lost = errorCode == TextToSpeech.ERROR_SERVICE)
        }
        // Only after stop(), whose generation the queue has already left behind.
        override fun onStop(id: String?, interrupted: Boolean) { if (id != null) callbacks.onSynthError(id, lost = false) }
      })
    }
    callbacks.onReady(status)
  }

  private fun chooseVoice(tts: TextToSpeech): EngineStatus {
    val voice = chooseVoice(tts, preferredVoice) ?: return EngineStatus.NO_VOICE
    return if (tts.setVoice(voice) == TextToSpeech.SUCCESS) EngineStatus.READY else EngineStatus.NO_VOICE
  }

  override fun synthesize(id: String, text: String, file: File): Boolean =
    runCatching { tts?.synthesizeToFile(text, Bundle(), file, id) == TextToSpeech.SUCCESS }.getOrDefault(false)

  /** getVoice goes to the engine; with the binding gone it answers null (as TtsSynth.alive). */
  override fun alive(): Boolean = runCatching { tts?.voice }.getOrNull() != null

  override fun stop() {
    runCatching { tts?.stop() }
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
