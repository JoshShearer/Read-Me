package io.loopstring.readme.playback

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log

/**
 * REA-32: TextToSpeech's constructor binds the engine, and when Android refuses the bind it
 * throws instead of reporting an init error, so the framework's own fall-back to another engine
 * never runs. On Android 10 (Huawei MatePad, 2026-10-03) Supertonic's and Marmalade's services
 * require BIND_TEXT_TO_SPEECH_SERVICE, a permission that only exists from Android 14, so no app
 * can bind them there: `SecurityException: Not allowed to bind to service`, thrown out of
 * PlaybackService.onCreate, crashed Read Me on every start.
 *
 * [engine] does the fall-back Android meant to: when the default engine cannot be bound, the
 * first installed engine that can (system engines first, as Android ranks them). Every caller
 * asks it, so playback, the probe and the bridge agree on one engine. A bind still refused is a
 * failed init: [open] posts ERROR, like the framework's callback, and the caller reports no-engine.
 * A rebind pinned to an engine never falls back (REA-29: a stop, not a different voice).
 */
object TtsOpen {
  class Candidate(val pkg: String, val bindable: Boolean, val system: Boolean)

  /** The engine to request: null for Android's default, else the package to use instead. */
  fun engine(context: Context): String? {
    val default = Settings.Secure.getString(context.contentResolver, "tts_default_synth")
    return pick(default, candidates(context))
  }

  fun pick(default: String?, engines: List<Candidate>): String? {
    val d = engines.firstOrNull { it.pkg == default }
    // Not installed, or bindable: Android's own choice is right.
    if (d == null || d.bindable) return null
    return engines.filter { it.bindable }.sortedByDescending { it.system }.firstOrNull()?.pkg ?: default
  }

  private fun candidates(context: Context): List<Candidate> {
    val pm = context.packageManager
    return runCatching { pm.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0) }
      .getOrNull().orEmpty()
      .mapNotNull { r -> r.serviceInfo }
      .map { s ->
        val p = s.permission
        Candidate(
          s.packageName,
          p == null || context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED,
          (s.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
        )
      }
  }

  fun open(main: Handler, construct: () -> TextToSpeech, onInit: (Int) -> Unit): TextToSpeech? =
    try {
      construct()
    } catch (e: RuntimeException) {
      // The class only: the message names the engine's package, not item text, but nothing more is needed.
      Log.w(TAG, "tts bind refused: ${e.javaClass.simpleName}")
      main.post { onInit(TextToSpeech.ERROR) }
      null
    }

  private const val TAG = "ReadMe"
}
