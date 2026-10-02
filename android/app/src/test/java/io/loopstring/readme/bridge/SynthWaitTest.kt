package io.loopstring.readme.bridge

import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SynthWaitTest {
  @Test fun doneIsOk() {
    val w = SynthWait()
    w.begin("b1")
    thread { Thread.sleep(50); w.finish("b1", SynthResult.OK) }
    assertEquals(SynthResult.OK, w.await(2_000))
  }

  @Test fun anotherIdIsIgnored() {
    val w = SynthWait()
    w.begin("b2")
    w.finish("b1", SynthResult.OK)
    assertEquals(SynthResult.TIMEOUT, w.await(100))
  }

  @Test fun cancelWinsOverALateDone() {
    val w = SynthWait()
    w.begin("b1")
    assertTrue(w.cancel())
    w.finish("b1", SynthResult.OK)
    assertEquals(SynthResult.CANCELLED, w.await(1_000))
  }

  @Test fun cancelWithNothingInFlightIsFalse() {
    val w = SynthWait()
    assertFalse(w.cancel())
    w.begin("b1")
    w.finish("b1", SynthResult.FAILED)
    assertEquals(SynthResult.FAILED, w.await(1_000))
    assertFalse(w.cancel())
  }
}
