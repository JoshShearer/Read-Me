package io.loopstring.readme.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REA-18, ADR 0009: Android stopped a paused, backgrounded service after 60 s (reference
 * device), taking the headset and notification controls with it. A user pause now keeps the
 * foreground for 30 minutes.
 */
class PauseWindowTest {
  private val t = 1_000_000L

  @Test fun heldRightAfterAPause() = assertTrue(PauseWindow.held(pausedAt = t, now = t))
  @Test fun heldJustInsideThirtyMinutes() = assertTrue(PauseWindow.held(t, t + PauseWindow.HOLD_MS - 1))
  @Test fun releasedAtThirtyMinutes() = assertFalse(PauseWindow.held(t, t + PauseWindow.HOLD_MS))
  @Test fun notHeldWhenNotPaused() = assertFalse(PauseWindow.held(pausedAt = -1, now = t))
}
