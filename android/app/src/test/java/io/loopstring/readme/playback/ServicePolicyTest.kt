package io.loopstring.readme.playback

import io.loopstring.readme.playback.PlaybackCommands.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R-M12: what the service does about the bridge. */
class ServicePolicyTest {
  @Test fun bridgeActionsRouteToBridgeAndTheBridgeKeepsAnIdleServiceAlive() {
    assertEquals(Route.BRIDGE, PlaybackCommands.route(PlaybackCommands.ACTION_BRIDGE, false, false))
    assertEquals(Route.BRIDGE, PlaybackCommands.route(PlaybackCommands.ACTION_BRIDGE_OFF, false, true))
    assertEquals(Route.IGNORE, PlaybackCommands.route(null, false, false, bridgeOn = true))
    assertEquals(Route.IGNORE, PlaybackCommands.route(PlaybackCommands.ACTION_PLAY, false, false, bridgeOn = true))
    assertEquals(Route.STOP, PlaybackCommands.route(null, false, false, bridgeOn = false))
  }

  @Test fun theBridgeKeepsTheServiceInTheForeground() {
    assertTrue(ServiceLife.foreground(playing = false, pausedForFocus = false, bridgeOn = true))
    assertFalse(ServiceLife.foreground(playing = false, pausedForFocus = false, bridgeOn = false))
    assertTrue(ServiceLife.foreground(playing = true, pausedForFocus = false, bridgeOn = false))
    assertTrue(ServiceLife.keepAlive(hasItem = false, bridgeOn = true))
    assertFalse(ServiceLife.keepAlive(hasItem = false, bridgeOn = false))
  }

  @Test fun theNotificationSaysWhenTheBridgeIsOn() {
    val idle = PlaybackSnapshot(null, false, null, 2.0f)
    val playing = PlaybackSnapshot(1L, true, null, 2.0f)
    assertEquals(ServiceText.Text("Read Me", "Obsidian bridge on", false), ServiceText.of("", idle, true))
    assertEquals(ServiceText.Text("Title", "Reading · Obsidian bridge on", true), ServiceText.of("Title", playing, true))
    assertEquals(ServiceText.Text("Title", "Paused", true), ServiceText.of("Title", playing.copy(playing = false), false))
  }
}
