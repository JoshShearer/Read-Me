package io.loopstring.readme.playback

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackHubTest {
  @After fun tearDown() = PlaybackHub.resetForTest()

  @Test fun aRequestIsTakenOnce() {
    val r = PlaybackHub.Request(1, "t", listOf(SentenceRow(0, 0, 1, "a")), 0)
    PlaybackHub.offer(r)
    assertTrue(PlaybackHub.hasPending())
    assertEquals(r, PlaybackHub.take())
    assertNull(PlaybackHub.take())
  }

  @Test fun aClaimedRequestCountsAsStartingUntilReleased() {
    // REA-35 critique: the service took the request and only later held it in `waiting`; a
    // Pause from the JS thread in between saw nothing starting and the Play went ahead.
    val r = PlaybackHub.Request(4, "t", listOf(SentenceRow(0, 0, 1, "a")), 0)
    PlaybackHub.offer(r)
    assertEquals(4L, PlaybackHub.startingItemId())
    assertEquals(r, PlaybackHub.claim())
    assertFalse(PlaybackHub.hasPending())
    assertNull(PlaybackHub.take()) // the JS thread cannot take what the service claimed
    assertEquals(4L, PlaybackHub.startingItemId())
    PlaybackHub.release()
    assertNull(PlaybackHub.startingItemId())
  }

  @Test fun publishingSetsSpeakingAndNotifiesListeners() {
    // ADR 0004: the bridge reads `speaking` to answer 503 while Read Me plays.
    val seen = mutableListOf<PlaybackSnapshot>()
    val l: (PlaybackSnapshot) -> Unit = { seen += it }
    PlaybackHub.addListener(l)
    PlaybackHub.publish(PlaybackSnapshot(1, true, null, 2f))
    assertTrue(PlaybackHub.speaking)
    PlaybackHub.publish(PlaybackSnapshot(1, false, null, 2f))
    assertFalse(PlaybackHub.speaking)
    PlaybackHub.removeListener(l)
    PlaybackHub.publish(PlaybackSnapshot(null, false, null, 2f))
    assertEquals(2, seen.size)
    assertNull(PlaybackHub.last.itemId)
  }

  @Test fun controlWithNoServiceIsFalse() {
    assertFalse(PlaybackHub.control(PlaybackCommands.ACTION_PAUSE))
    PlaybackHub.controller = { it == PlaybackCommands.ACTION_PAUSE }
    assertTrue(PlaybackHub.control(PlaybackCommands.ACTION_PAUSE))
  }
}

class PlaybackHubStopItemTest {
  @After fun tearDown() = PlaybackHub.resetForTest()

  @Test fun stoppingAnItemDropsItsPendingRequestOnly() {
    // Review: a delete during the engine's cold start must not be read aloud later.
    PlaybackHub.offer(PlaybackHub.Request(1, "t", listOf(SentenceRow(0, 0, 1, "a")), 0))
    PlaybackHub.stopItem(2)
    assertTrue(PlaybackHub.hasPending())
    PlaybackHub.stopItem(1)
    assertFalse(PlaybackHub.hasPending())
  }

  @Test fun bridgeStatusIsPublishedToListeners() {
    val seen = mutableListOf<BridgeStatus>()
    PlaybackHub.addBridgeListener { seen += it }
    PlaybackHub.publishBridge(BridgeStatus("on", 8787, null))
    assertEquals(BridgeStatus("on", 8787, null), PlaybackHub.bridge)
    assertEquals(listOf(BridgeStatus("on", 8787, null)), seen)
    PlaybackHub.resetForTest()
    assertEquals(BridgeStatus.OFF, PlaybackHub.bridge)
  }
}
