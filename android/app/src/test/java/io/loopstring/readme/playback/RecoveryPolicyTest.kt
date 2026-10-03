package io.loopstring.readme.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** REA-18: a dead TTS engine is rebound once per user Play, never in a loop. */
class RecoveryPolicyTest {
  @Test fun theFirstLossRebinds() = assertTrue(RecoveryPolicy.rebind(rebindsSinceUserPlay = 0, rebinding = false))

  @Test fun aSecondLossBeforeTheUserPlaysAgainStaysPaused() =
    assertFalse(RecoveryPolicy.rebind(rebindsSinceUserPlay = 1, rebinding = false))

  @Test fun aLossWhileRebindingIsIgnored() = assertFalse(RecoveryPolicy.rebind(rebindsSinceUserPlay = 0, rebinding = true))
}
