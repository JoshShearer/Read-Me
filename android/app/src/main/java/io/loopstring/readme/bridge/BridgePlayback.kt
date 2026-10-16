package io.loopstring.readme.bridge

/**
 * REA-39, ADR 0014: Read Me reads the plugin's text aloud itself, in its own foreground service,
 * so Android's audio hardening cannot mute it when Obsidian leaves the screen (it muted
 * Obsidian's own playback about 5 s after Obsidian stopped, REA-39 2026-10-08). A session is the
 * plugin's chunks, in order; [Status.index] is the chunk being read. Plain JVM, so the bridge's
 * socket tests can drive it with a fake. Implementations never log the text (AGENTS.md 1).
 */
interface BridgePlayback {
  /**
   * Starts reading [chunks] at [rate] as a new session, replacing an earlier session. Null when
   * Read Me is reading one of its own items (ADR 0004: its playback has the engine).
   */
  fun play(chunks: List<String>, rate: Float): Int?

  /** The newest session's state; session 0 and [State.NONE] before the first. */
  fun status(): Status

  /** [action] on [session]; null when [session] is not the newest (a stale plugin). */
  fun control(session: Int, action: Action): Status?

  enum class Action { PAUSE, RESUME, STOP }

  /**
   * [State.ENDED]: the last chunk was read. [State.STOPPED]: stopped, or replaced by Read Me's
   * own playback. [index] is the chunk being read (or paused in), the last one read once ended.
   */
  enum class State(val wire: String) { NONE("none"), STARTING("starting"), PLAYING("playing"), PAUSED("paused"), ENDED("ended"), STOPPED("stopped") }

  data class Status(val session: Int, val state: State, val index: Int)
}
