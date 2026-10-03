package io.loopstring.readme

import android.util.Log
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate
import io.loopstring.readme.playback.PlaybackHub
import io.loopstring.readme.playback.PlaybackService
import io.loopstring.readme.store.Settings

class MainActivity : ReactActivity() {

  /**
   * Returns the name of the main component registered from JavaScript. This is used to schedule
   * rendering of the component.
   */
  override fun getMainComponentName(): String = "ReadMe"

  /**
   * Returns the instance of the [ReactActivityDelegate]. We use [DefaultReactActivityDelegate]
   * which allows you to enable New Architecture with a single boolean flags [fabricEnabled]
   */
  override fun createReactActivityDelegate(): ReactActivityDelegate =
      DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)

  /** R-M12: after a reboot or process death the bridge returns when Read Me is next opened. */
  override fun onResume() {
    super.onResume()
    try {
      if (Settings(this).bridgeEnabled && PlaybackHub.bridge.state != "on") PlaybackService.syncBridge(this)
    } catch (e: IllegalStateException) {
      Log.i("ReadMe", "bridge start refused: ${e.javaClass.simpleName}")
    }
  }
}
