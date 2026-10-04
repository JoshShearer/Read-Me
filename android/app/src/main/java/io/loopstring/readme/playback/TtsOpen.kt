package io.loopstring.readme.playback

import android.os.Handler
import android.speech.tts.TextToSpeech
import android.util.Log

/**
 * REA-32: TextToSpeech's constructor binds the engine, and when Android refuses the bind it
 * throws instead of reporting an init error. On Android 10 (Huawei MatePad, 2026-10-03) the
 * Supertonic engine's service requires BIND_TEXT_TO_SPEECH_SERVICE, which only the system holds:
 * `SecurityException: Not allowed to bind to service`, thrown out of PlaybackService.onCreate,
 * crashed Read Me on every start. A refused bind is a failed init: [onInit] gets ERROR, posted
 * like the framework's own callback, so the caller reports no-engine and the R-M10 card shows.
 * No other engine is tried (REA-29: a stop, not a different voice).
 */
object TtsOpen {
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
