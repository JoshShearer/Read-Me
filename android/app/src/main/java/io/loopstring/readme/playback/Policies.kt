package io.loopstring.readme.playback

import android.media.AudioManager
import java.util.Locale

/** A TextToSpeech Voice reduced to what the choice needs, so the rule is testable. */
data class VoiceInfo(
  val name: String,
  val language: String,
  val networkRequired: Boolean,
  val notInstalled: Boolean,
  val quality: Int,
  /** The full locale as a BCP 47 tag ("en-US"); [language] is only its language part. */
  val tag: String = language,
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

/**
 * Settings' voice list: offline, installed voices, each name once (iFlytek on the Huawei tablet
 * listed "en" twice, 2026-10-03); the default's language first, then quality.
 */
object VoiceList {
  fun usable(voices: List<VoiceInfo>, language: String): List<VoiceInfo> =
    voices.filter(VoicePicker::usable)
      .distinctBy { it.name }
      .sortedWith(compareBy<VoiceInfo>({ it.language != language }, { -it.quality }, { it.name }))
}

/**
 * What Settings shows for a voice. Engines name voices for machines: iFlytek's are bare language
 * codes ("en", "agq") and Google's are like "en-us-x-sfg-local", so a name that looks like a
 * locale is replaced by the locale's name in the phone's language ("English (United States)").
 * A name a person gave (Marmalade's, say) is kept, without a model prefix, with the language as its detail. Voices that
 * would read the same are numbered, in name order.
 */
object VoiceLabels {
  data class Label(val label: String, val detail: String)

  private val MACHINE = Regex("^[a-z]{2,3}([-_][A-Za-z0-9_#-]*)?$")

  fun of(voices: List<VoiceInfo>, display: Locale = Locale.getDefault()): Map<String, Label> {
    // Language and country only: iFlytek's English voice carried a script that read as
    // "English (Zawgyi)" on the Huawei tablet (2026-10-03).
    fun language(v: VoiceInfo): String {
      val l = Locale.forLanguageTag(v.tag.replace('_', '-'))
      return Locale(l.language, l.country).getDisplayName(display).ifEmpty { v.tag }
    }
    // A quality every voice shares says nothing, so it is shown only when voices differ.
    val qualities = voices.map { it.quality }.distinct().size > 1
    val base = voices.associate { v ->
      v.name to if (MACHINE.matches(v.name)) Label(language(v), if (qualities) quality(v.quality) else "") else Label(spoken(v.name), language(v))
    }
    val out = HashMap<String, Label>()
    voices.groupBy { base.getValue(it.name).label }.forEach { (_, same) ->
      if (same.size == 1) {
        out[same[0].name] = base.getValue(same[0].name)
      } else {
        same.sortedBy { it.name }.forEachIndexed { i, v ->
          val b = base.getValue(v.name)
          val rest = if (b.detail.isEmpty()) "" else ", ${b.detail.replaceFirstChar { it.lowercase(display) }}"
          out[v.name] = Label(b.label, "${speaker(v.name) ?: "Voice ${i + 1}"}$rest")
        }
      }
    }
    return out
  }

  // Supertonic names its voices "en-supertonic-F1" .. "-M5" (reference device, 2026-10-03).
  private val SPEAKER = Regex("[-_]([FfMm])(\\d{1,2})$")

  /** Marmalade prefixes its model: "kitten-direct-v0_8:Bruno" reads as "Bruno" (reference device, 2026-10-03). */
  fun spoken(name: String): String = name.substringAfterLast(':').trim().ifEmpty { name }

  /** "Female 1" for a name ending in F1, "Male 2" for M2; null otherwise. */
  fun speaker(name: String): String? = SPEAKER.find(name)?.let { m ->
    (if (m.groupValues[1].equals("F", ignoreCase = true)) "Female " else "Male ") + m.groupValues[2]
  }

  fun quality(q: Int): String = when {
    q >= 500 -> "Very high quality"
    q >= 400 -> "High quality"
    q >= 300 -> "Normal quality"
    q >= 200 -> "Low quality"
    else -> "Very low quality"
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
 * neither focus nor the receiver; its foreground is ServiceLife's (ADR 0009).
 */
object PausePolicy {
  fun hold(pausedForFocus: Boolean) = PauseHold(pausedForFocus, pausedForFocus, pausedForFocus)
}

/**
 * R-M12, ADR 0005: the bridge needs the foreground service whether or not anything plays.
 * ADR 0009: a user pause holds it for PauseWindow.HOLD_MS.
 */
object ServiceLife {
  fun foreground(playing: Boolean, pausedForFocus: Boolean, bridgeOn: Boolean, pauseHeld: Boolean = false) =
    playing || PausePolicy.hold(pausedForFocus).foreground || pauseHeld || bridgeOn

  fun keepAlive(hasItem: Boolean, bridgeOn: Boolean) = hasItem || bridgeOn
}

/**
 * ADR 0009: how long a user-paused session stays in the foreground, so lock-screen,
 * notification and headset Play keep working. [pausedAt] is elapsedRealtime, or -1.
 */
object PauseWindow {
  const val HOLD_MS = 30 * 60 * 1000L
  fun held(pausedAt: Long, now: Long) = pausedAt >= 0 && now - pausedAt < HOLD_MS
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
 * REA-35: what getEngine reports. A live service's engine is the truth while it exists: a probe
 * (its own short-lived instance, AGENTS.md 13) can time out on a busy engine, and must not show
 * the blocking card while the service reads aloud, nor overwrite the state the service publishes.
 */
object EnginePolicy {
  data class Answer(val status: String, val write: Boolean)

  fun answer(probe: String, serviceAlive: Boolean, serviceEngine: String): Answer = when {
    serviceAlive && serviceEngine == "ready" -> Answer("ready", write = false)
    serviceAlive -> Answer(probe, write = false)
    else -> Answer(probe, write = true)
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
