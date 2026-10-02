package io.loopstring.readme

import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate
import io.loopstring.readme.spike.SpikeService

class MainActivity : ReactActivity() {

  override fun getMainComponentName(): String = "ReadMe"

  override fun createReactActivityDelegate(): ReactActivityDelegate =
      DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    forwardNativeSpike(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    forwardNativeSpike(intent)
  }

  /**
   * Spike branch only. adb (uid 2000) cannot start the unexported SpikeService: AOSP lets only
   * root and system reach unexported components. So scripts start this exported activity with
   * `--ez nativeSpike true` plus the spike extras, and it forwards them while in the foreground.
   */
  private fun forwardNativeSpike(intent: Intent?) {
    if (intent?.getBooleanExtra("nativeSpike", false) != true) return
    val forward = Intent(this, SpikeService::class.java).putExtras(intent.extras ?: return)
    if (Build.VERSION.SDK_INT >= 26) startForegroundService(forward) else startService(forward)
  }
}
