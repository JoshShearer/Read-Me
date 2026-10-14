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
class TtsSpeaker(
  context: Context,
  private val callbacks: Callbacks,
  private val preferredVoice: String? = null,
  startTimeoutMs: Long = START_TIMEOUT_MS,
) : SentenceSynth {
  interface Callbacks {
    fun onReady(status: EngineStatus)
    fun onSynthDone(id: String)
    /** [lost]: ERROR_SERVICE, the engine's process is gone. */
    fun onSynthError(id: String, lost: Boolean)
    /**
     * REA-18: [speaker]'s engine did not answer within its start timeout. The speaker is shut
     * down and its status is NO_ENGINE; it never calls onReady after this.
     */
    fun onStartTimedOut(speaker: TtsSpeaker)
  }

  enum class EngineStatus(val wire: String) {
    PENDING("pending"), READY("ready"), NO_ENGINE("no-engine"), NO_VOICE("no-voice")
  }

  private val main = Handler(Looper.getMainLooper())

  @Volatile var status = EngineStatus.PENDING
    private set

  // Set by shutdown and by the start timeout: from then on this speaker answers nothing (a late
  // onInit included) and asks the engine for nothing.
  @Volatile private var shut = false

  /** REA-18: the engine never answered onInit within the start timeout. */
  @Volatile var timedOut = false
    private set

  private val startTimeout = Runnable {
    if (shut || status != EngineStatus.PENDING) return@Runnable
    timedOut = true
    shut = true
    status = EngineStatus.NO_ENGINE
    runCatching { tts?.shutdown() }
    callbacks.onStartTimedOut(this)
  }

  // onInit can run synchronously inside this constructor when the engine fails to bind, before
  // `tts` is assigned (SPIKE-05 probe). Posting defers every use of `tts` until it exists.
  // Null when Android refused the bind (REA-32): onInit then gets ERROR and status is NO_ENGINE.
  private val tts: TextToSpeech? =
    TtsOpen.open(main, { TextToSpeech(context.applicationContext, { s -> main.post { onInit(s) } }, TtsOpen.engine(context)) }) { onInit(it) }

  init {
    // Posted after `tts` exists; a refused bind has already queued its onInit, which runs first.
    main.postDelayed(startTimeout, startTimeoutMs)
  }

  private fun onInit(result: Int) {
    main.removeCallbacks(startTimeout)
    // A retired, destroyed or timed-out speaker's late answer: nothing listens for it any more.
    if (shut) return
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
        // Only after stop(), whose generation the queue has already left behind: the queue flushes
        // (a new generation) before each stop() it makes, and PlaybackService shuts an old
        // speaker down only through swapSynth's retire, after the swap's flush (REA-51).
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
    !shut && runCatching { tts?.synthesizeToFile(text, Bundle(), file, id) == TextToSpeech.SUCCESS }.getOrDefault(false)

  /** getVoice goes to the engine; with the binding gone it answers null (as TtsSynth.alive). */
  override fun alive(): Boolean = !shut && runCatching { tts?.voice }.getOrNull() != null

  override fun stop() {
    runCatching { tts?.stop() }
  }

  override fun setRate(rate: Float) {
    tts?.setSpeechRate(rate)
  }

  fun shutdown() {
    main.removeCallbacks(startTimeout)
    // Once only: the start timeout has already shut a timed-out engine down.
    if (shut) return
    shut = true
    runCatching { tts?.stop() }
    runCatching { tts?.shutdown() }
  }

  companion object {
    /**
     * REA-18: how long an engine may take to answer onInit before the speaker counts as having
     * no engine. The slowest cold engine measured took 8056 ms to first audio, init included
     * (AGENTS.md, Playback); EngineProbe gives up after 20 s on a probe that shows a card, and a
     * speaker that is about to read gets longer.
     */
    const val START_TIMEOUT_MS = 30_000L

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
