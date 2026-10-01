package io.loopstring.readme

import android.os.Bundle
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate

class MainActivity : ReactActivity() {

  override fun getMainComponentName(): String = "ReadMe"

  /**
   * Spike branch only: `am start ... --es spike <name>` reaches the root component as the
   * `spike` initial prop, so JS spikes run in the release build without a debug menu.
   */
  override fun createReactActivityDelegate(): ReactActivityDelegate =
      object : DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled) {
        override fun getLaunchOptions(): Bundle? =
            intent?.getStringExtra("spike")?.let { Bundle().apply { putString("spike", it) } }
      }
}
