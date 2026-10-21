package io.loopstring.readme

import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.facebook.react.bridge.BridgeReactContext
import com.facebook.react.bridge.Promise
import io.loopstring.readme.playback.PlaybackHub
import io.loopstring.readme.playback.PlaybackService
import io.loopstring.readme.store.Settings
import io.loopstring.readme.store.Store
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.lang.reflect.Proxy

/**
 * REA-60, R-M12: every module path that syncs the bridge starts nothing while Read Me's
 * notifications cannot show. Side effects only: resolving a map needs React's native library,
 * which throws on the JVM after the part under test has run.
 */
@RunWith(RobolectricTestRunner::class)
class ReadMeSpeechModuleBridgeGuardTest {
  private val app get() = ApplicationProvider.getApplicationContext<Application>()
  private val promise = Proxy.newProxyInstance(Promise::class.java.classLoader, arrayOf(Promise::class.java)) { _, _, _ -> null } as Promise
  private lateinit var port: Number

  @Before fun setUp() {
    port = java.net.ServerSocket(0).use { it.localPort }
    Settings(app).apply { bridgePort = port.toInt(); bridgeEnabled = true }
    shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
  }

  @After fun tearDown() {
    Settings(app).apply { bridgeEnabled = false; voice = null; engine = null }
    PlaybackHub.resetForTest()
    Store.resetForTest()
    // Plan review F7: global, shared by every test class in this JVM.
    PlaybackService.notificationsShown = io.loopstring.readme.playback.NotificationGate::shown
  }

  private fun assertNothingStarted() {
    assertNull(shadowOf(app).nextStartedService)
    assertTrue(runCatching { java.net.Socket("127.0.0.1", port.toInt()).close() }.isFailure)
  }

  @Test fun setVoiceStartsNoBridge() {
    runCatching { ReadMeSpeechModule(BridgeReactContext(app)).setVoice("v", promise) }
    assertNothingStarted()
  }

  @Test fun setEngineStartsNoBridge() {
    runCatching { ReadMeSpeechModule(BridgeReactContext(app)).setEngine("p", promise) }
    assertNothingStarted()
  }

  @Test fun setBridgePortStartsNoBridge() {
    runCatching { ReadMeSpeechModule(BridgeReactContext(app)).setBridgePort(port.toDouble(), promise) }
    assertNothingStarted()
  }

  @Test fun setBridgeEnabledStartsNoBridge() {
    runCatching { ReadMeSpeechModule(BridgeReactContext(app)).setBridgeEnabled(true, promise) }
    assertNothingStarted()
  }
}
