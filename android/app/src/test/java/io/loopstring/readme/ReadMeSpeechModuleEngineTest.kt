package io.loopstring.readme

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.BridgeReactContext
import io.loopstring.readme.playback.PlaybackCommands
import io.loopstring.readme.playback.PlaybackHub
import io.loopstring.readme.playback.PlaybackService
import io.loopstring.readme.playback.SentenceRow
import io.loopstring.readme.playback.TtsSpeaker
import io.loopstring.readme.store.Store
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.lang.reflect.Proxy
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class ReadMeSpeechModuleEngineTest {
  @After fun tearDown() {
    PlaybackHub.resetForTest()
    Store.resetForTest()
  }

  @Test fun aProbeThatTimesOutNeverOverwritesALiveServicesReadyEngine() {
    // REA-35 #5: the Reader probes the engine on open. A probe that timed out while the service
    // was reading wrote "no-engine" into PlaybackHub.engine, so every later snapshot told the
    // Reader to show the blocking card while audio went on.
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val id = Store.get(ctx).insertText("t", listOf("Hello World"), 1L)
    PlaybackHub.offer(PlaybackHub.Request(id, "t", listOf(SentenceRow(0, 0, 11, "Hello World")), 0))
    val c = Robolectric.buildService(PlaybackService::class.java,
      Intent(ctx, PlaybackService::class.java).setAction(PlaybackCommands.ACTION_START)).create().startCommand(0, 1)
    shadowOf(Looper.getMainLooper()).idle()
    c.get().onReady(TtsSpeaker.EngineStatus.READY)
    shadowOf(Looper.getMainLooper()).idle()
    assertTrue(PlaybackHub.queue!!.snapshot().playing)
    assertEquals("ready", PlaybackHub.engine)

    val module = ReadMeSpeechModule(BridgeReactContext(ctx))
    // Robolectric's TextToSpeech never answers, so the probe ends by its timeout ("no-engine").
    // Resolving builds a WritableNativeMap, which needs React's native library: that throws here,
    // after the part under test has run, so it is caught and ignored.
    val promise = Proxy.newProxyInstance(Promise::class.java.classLoader, arrayOf(Promise::class.java)) { _, _, _ -> null } as Promise
    module.getEngine(promise)
    runCatching { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21)) }
    runCatching { shadowOf(Looper.getMainLooper()).idle() }
    assertEquals("ready", PlaybackHub.engine)
    c.destroy()
  }
}
