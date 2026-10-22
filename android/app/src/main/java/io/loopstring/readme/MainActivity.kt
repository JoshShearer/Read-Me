package io.loopstring.readme

import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate
import io.loopstring.readme.bridge.BridgeView
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

  // REA-63 (ADR 0016): a share that matched an unread item brings this activity (singleTask) up
  // with the item's id; JS takes it from SharedOpen.
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    SharedOpen.fromIntent(intent, savedInstanceState != null)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    SharedOpen.fromIntent(intent, false)
  }

  /**
   * R-M12: after a reboot or process death the bridge returns when Read Me is next opened, if
   * its notification can show (REA-60).
   */
  override fun onResume() {
    super.onResume()
    try {
      val shown = PlaybackService.notificationsShown(this)
      if (BridgeView.syncOnResume(Settings(this).bridgeEnabled, PlaybackHub.bridge.state, shown)) PlaybackService.syncBridge(this)
    } catch (e: IllegalStateException) {
      Log.i("ReadMe", "bridge start refused: ${e.javaClass.simpleName}")
    }
  }
}
