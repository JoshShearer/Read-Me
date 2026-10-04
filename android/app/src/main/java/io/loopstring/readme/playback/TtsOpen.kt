package io.loopstring.readme.playback

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import io.loopstring.readme.store.Settings as AppSettings

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
 *
 * Below Android 14 Settings offers the installed engines and [engine] honours the choice while it
 * can be bound; from Android 14 the system's engine is always used (owner decision, 2026-10-03).
 */
object TtsOpen {
  class Candidate(val pkg: String, val bindable: Boolean, val system: Boolean, val label: String = pkg)

  /** Whether Settings offers a choice of engine: below Android 14 only. */
  val choosable: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE

  /** The engine to request: null for Android's default, else the package to use instead. */
  fun engine(context: Context): String? {
    // From Android 14 the system's engine, always: it binds there (Supertonic on the reference
    // device), and the permission check below would wrongly call it unbindable.
    if (!choosable) return null
    val all = candidates(context)
    val chosen = if (choosable) AppSettings(context).engine else null
    return chosen(chosen, all) ?: pick(default(context), all)
  }

  /** The package Android treats as its default engine, or null. */
  fun default(context: Context): String? = Settings.Secure.getString(context.contentResolver, "tts_default_synth")

  fun chosen(chosen: String?, engines: List<Candidate>): String? =
    engines.firstOrNull { it.pkg == chosen && it.bindable }?.pkg

  fun pick(default: String?, engines: List<Candidate>): String? {
    val d = engines.firstOrNull { it.pkg == default }
    // Not installed, or bindable: Android's own choice is right.
    if (d == null || d.bindable) return null
    return engines.filter { it.bindable }.sortedByDescending { it.system }.firstOrNull()?.pkg ?: default
  }

  /** The installed engines, by label; [Candidate.bindable] is false where Android refuses the bind. */
  fun candidates(context: Context): List<Candidate> {
    val pm = context.packageManager
    return runCatching { pm.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0) }
      .getOrNull().orEmpty()
      .mapNotNull { r -> r.serviceInfo }
      .map { s ->
        val p = s.permission
        Candidate(
          s.packageName,
          p == null || context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED ||
            (!choosable && p == BIND_TTS),
          (s.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
          runCatching { s.applicationInfo.loadLabel(pm).toString() }.getOrNull() ?: s.packageName,
        )
      }
      .distinctBy { it.pkg }
      .sortedBy { it.label.lowercase() }
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
  private const val BIND_TTS = "android.permission.BIND_TEXT_TO_SPEECH_SERVICE"
}
