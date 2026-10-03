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
 * blocking "no offline voice" state). A network voice is never returned. A saved voice wins when it is still offline and installed.
 */
object VoicePicker {
  fun usable(v: VoiceInfo) = !v.networkRequired && !v.notInstalled

  fun pick(default: VoiceInfo?, voices: List<VoiceInfo>, language: String, preferred: String? = null): VoiceInfo? {
    if (preferred != null) voices.firstOrNull { it.name == preferred && usable(it) }?.let { return it }
    if (default != null && usable(default)) return default
    val offline = voices.filter(::usable)
    return offline.filter { it.language == language }.maxByOrNull { it.quality }
      ?: offline.maxByOrNull { it.quality }
  }
}

/** Settings' voice list: offline, installed voices; the default's language first, then quality. */
object VoiceList {
  fun usable(voices: List<VoiceInfo>, language: String): List<VoiceInfo> =
    voices.filter(VoicePicker::usable)
      .sortedWith(compareBy<VoiceInfo>({ it.language != language }, { -it.quality }, { it.name }))
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
  const val ACTION_BRIDGE = P + "BRIDGE"
  const val ACTION_BRIDGE_OFF = P + "BRIDGE_OFF"

  private val CONTROLS = setOf(
    ACTION_PLAY, ACTION_PAUSE, ACTION_TOGGLE, ACTION_NEXT, ACTION_PREVIOUS, ACTION_BACK_PARAGRAPH, ACTION_STOP,
  )

  enum class Route { START, CONTROL, BRIDGE, IGNORE, STOP }

  fun route(action: String?, hasPending: Boolean, hasItem: Boolean, bridgeOn: Boolean = false): Route = when {
    action == ACTION_START && hasPending -> Route.START
    action == ACTION_BRIDGE || action == ACTION_BRIDGE_OFF -> Route.BRIDGE
    action in CONTROLS && hasItem -> Route.CONTROL
    hasItem || bridgeOn -> Route.IGNORE
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

/** R-M12, ADR 0005: the bridge needs the foreground service whether or not anything plays. */
object ServiceLife {
  fun foreground(playing: Boolean, pausedForFocus: Boolean, bridgeOn: Boolean) =
    playing || PausePolicy.hold(pausedForFocus).foreground || bridgeOn

  fun keepAlive(hasItem: Boolean, bridgeOn: Boolean) = hasItem || bridgeOn
}

/** R-M12: "its foreground notification MUST say so". */
object ServiceText {
  data class Text(val title: String, val body: String, val media: Boolean)

  fun of(itemTitle: String, s: PlaybackSnapshot, bridgeOn: Boolean): Text {
    if (s.itemId == null) return Text("Read Me", if (bridgeOn) "Obsidian bridge on" else "Starting", false)
    val state = if (s.playing) "Reading" else "Paused"
    return Text(itemTitle, if (bridgeOn) "$state · Obsidian bridge on" else state, true)
  }
}

/**
 * REA-18: when playback's engine dies (its process killed or crashed), TextToSpeech does not
 * rebind by itself. The service rebinds once and resumes; a second loss before the user presses
 * Play again leaves playback paused at the saved position.
 */
object RecoveryPolicy {
  const val MAX_REBINDS = 1
  fun rebind(rebindsSinceUserPlay: Int, rebinding: Boolean) = !rebinding && rebindsSinceUserPlay < MAX_REBINDS
}
