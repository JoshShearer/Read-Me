package io.loopstring.readme.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PausePolicyTest {
  @Test fun aFocusPauseKeepsWhatResumingNeeds() {
    // Resuming after a call with the screen off needs the foreground (Android 12+ refuses a
    // background start), focus, and the noisy receiver (headphones unplugged meanwhile).
    assertEquals(PauseHold(foreground = true, noisy = true, focus = true), PausePolicy.hold(pausedForFocus = true))
  }

  @Test fun aUserPauseReleasesEverything() {
    assertEquals(PauseHold(foreground = false, noisy = false, focus = false), PausePolicy.hold(pausedForFocus = false))
  }
}
