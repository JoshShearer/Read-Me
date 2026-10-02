package io.loopstring.readme.playback

import android.media.AudioManager

/** A TextToSpeech Voice reduced to what the choice needs, so the rule is testable. */
data class VoiceInfo(
  val name: String,
  val language: String,
  val networkRequired: Boolean,
  val notInstalled: Boolean,
  val quality: Int,
)

/**
 * R-M06, AGENTS.md 5: the engine's default voice if it is offline and installed, else the best
 * offline voice in the default's language, else the best offline voice at all, else null (the
 * blocking "no offline voice" state). A network voice is never returned.
 */
object VoicePicker {
  fun pick(default: VoiceInfo?, voices: List<VoiceInfo>, language: String): VoiceInfo? {
    fun usable(v: VoiceInfo) = !v.networkRequired && !v.notInstalled
    if (default != null && usable(default)) return default
    val offline = voices.filter(::usable)
    return offline.filter { it.language == language }.maxByOrNull { it.quality }
      ?: offline.maxByOrNull { it.quality }
  }
}

enum class FocusAction { PAUSE, PAUSE_TRANSIENT, RESUME, NONE }

/** R-M07: pause on any loss; resume on gain only what a transient loss paused. */
object FocusPolicy {
  fun onChange(change: Int, pausedForFocus: Boolean): FocusAction = when (change) {
    AudioManager.AUDIOFOCUS_LOSS -> FocusAction.PAUSE
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> FocusAction.PAUSE_TRANSIENT
    AudioManager.AUDIOFOCUS_GAIN -> if (pausedForFocus) FocusAction.RESUME else FocusAction.NONE
    else -> FocusAction.NONE
  }
}

/**
 * What PlaybackService does with an intent. A notification action can arrive after the process
 * died: the new process has no queue and no request, so the service stops instead of sitting
 * in the foreground with nothing to say.
 */
object PlaybackCommands {
  private const val P = "io.loopstring.readme.playback."
  const val ACTION_START = P + "START"
  const val ACTION_PLAY = P + "PLAY"
  const val ACTION_PAUSE = P + "PAUSE"
  const val ACTION_TOGGLE = P + "TOGGLE"
  const val ACTION_NEXT = P + "NEXT"
  const val ACTION_PREVIOUS = P + "PREVIOUS"
  const val ACTION_BACK_PARAGRAPH = P + "BACK_PARAGRAPH"
  const val ACTION_STOP = P + "STOP"

  private val CONTROLS = setOf(
    ACTION_PLAY, ACTION_PAUSE, ACTION_TOGGLE, ACTION_NEXT, ACTION_PREVIOUS, ACTION_BACK_PARAGRAPH, ACTION_STOP,
  )

  enum class Route { START, CONTROL, IGNORE, STOP }

  fun route(action: String?, hasPending: Boolean, hasItem: Boolean): Route = when {
    action == ACTION_START && hasPending -> Route.START
    action in CONTROLS && hasItem -> Route.CONTROL
    hasItem -> Route.IGNORE
    else -> Route.STOP
  }
}

/** What a paused service keeps. */
data class PauseHold(val foreground: Boolean, val noisy: Boolean, val focus: Boolean)

/**
 * A transient focus loss (a call, a navigation prompt) pauses playback the user still wants:
 * keep the foreground (Android 12+ refuses a foreground start from the background, so a resume
 * on focus gain with the screen off would fail), the focus request (to hear the gain) and the
 * noisy receiver (headphones unplugged meanwhile must cancel that resume). A user pause keeps
 * nothing.
 */
object PausePolicy {
  fun hold(pausedForFocus: Boolean) = PauseHold(pausedForFocus, pausedForFocus, pausedForFocus)
}
