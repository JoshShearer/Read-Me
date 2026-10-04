package io.loopstring.readme.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * R-M06 / R-M10: answers "is there an engine and an offline voice?" at launch and for Settings,
 * before any play attempt. A short-lived instance that binds, reads the voice list and shuts
 * down; it never speaks. Assumed not to disturb playback's instance: SPIKE-06 measured
 * contention while a second instance synthesized, never a bind-and-read like this, so it is
 * unmeasured. Start on any thread; `done` runs once, on the main thread.
 */
class EngineProbe private constructor(
  context: Context,
  private val preferred: String?,
  private val done: (Report) -> Unit,
) {
  /**
   * [engine] is the package bound (null when none was); [engines] lists every installed engine
   * when Settings may choose one (below Android 14), else is empty.
   */
  data class Report(
    val status: String,
    val voices: List<VoiceInfo>,
    val selected: String?,
    val engine: String? = null,
    val engines: List<TtsOpen.Candidate> = emptyList(),
  )

  private val main = Handler(Looper.getMainLooper())
  private val app = context.applicationContext
  private val requested = TtsOpen.engine(app)
  private var finished = false
  private val timeout = Runnable { finish(Report("no-engine", emptyList(), null)) }

  // onInit can run inside the constructor when binding fails (SPIKE-05 probe): post it.
  // Null when Android refused the bind (REA-32): onInit then gets ERROR and reports no-engine.
  private val tts: TextToSpeech? =
    TtsOpen.open(main, { TextToSpeech(app, { s -> main.post { onInit(s) } }, requested) }) { onInit(it) }

  init {
    main.postDelayed(timeout, TIMEOUT_MS)
  }

  private fun onInit(status: Int) {
    val tts = tts
    if (status != TextToSpeech.SUCCESS || tts == null) return finish(Report("no-engine", emptyList(), null))
    val all = runCatching { tts.voices }.getOrNull().orEmpty().map { TtsSpeaker.info(it) }
    val default = runCatching { tts.defaultVoice }.getOrNull()?.let { TtsSpeaker.info(it) }
    val language = default?.language ?: Locale.getDefault().language
    val usable = VoiceList.usable(all, language)
    val selected = VoicePicker.pick(default, all, language, preferred)
    val engine = requested ?: runCatching { tts.defaultEngine }.getOrNull()
    finish(Report(if (selected == null) "no-voice" else "ready", usable, selected?.name, engine))
  }

  private fun finish(base: Report) {
    if (finished) return
    finished = true
    val r = if (TtsOpen.choosable) base.copy(engines = TtsOpen.candidates(app)) else base
    main.removeCallbacks(timeout)
    runCatching { tts?.shutdown() }
    done(r)
  }

  companion object {
    // A cold engine took 8 s to first audio once on the reference device (Phase 3); binding is
    // part of that, so allow well past it.
    private const val TIMEOUT_MS = 20_000L

    fun start(context: Context, preferred: String?, done: (Report) -> Unit) {
      Handler(Looper.getMainLooper()).post { EngineProbe(context, preferred, done) }
    }
  }
}
