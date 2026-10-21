package io.loopstring.readme.bridge

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle

/** What Settings shows for the bridge: Settings says whether it should run, the service whether it does. */
object BridgeView {
  /** R-M12 (REA-60): the bridge runs only while Settings has it on and its notification can show. */
  fun allowed(enabled: Boolean, notificationsShown: Boolean): Boolean = enabled && notificationsShown

  /**
   * REA-23: the hub's on or failed counts only for the port Settings holds; until the service
   * reports that port (a port change restarts the server), Settings shows starting. REA-60:
   * blocked while notifications cannot show; the saved setting stays on.
   */
  fun state(enabled: Boolean, notificationsShown: Boolean, hub: String, hubPort: Int, port: Int): String = when {
    !enabled -> "off"
    !notificationsShown -> "blocked"
    (hub == "on" || hub == "failed") && hubPort == port -> hub
    else -> "starting"
  }

  /**
   * MainActivity.onResume restarts the bridge only when the service or process is gone (hub
   * off). A failed bind (port taken) is retried by a port change or a toggle: retrying it on
   * every resume flashed the Starting notification (REA-23). REA-60: while notifications cannot
   * show, a saved-on bridge goes to PlaybackService.syncBridge, which refuses and logs it, and a
   * running bridge goes there to be stopped (a pm revoke sends no broadcast, so opening the app
   * is the floor).
   */
  fun syncOnResume(enabled: Boolean, hub: String, notificationsShown: Boolean): Boolean =
    if (notificationsShown) enabled && hub == "off" else enabled || hub == "on"
}

/** R-M12 "shown in Settings with a Copy button". Marked sensitive so Android 13+ hides it from the clipboard preview. */
object TokenClipboard {
  fun copy(context: Context, token: String) {
    val clip = ClipData.newPlainText("Read Me bridge token", token)
    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
  }
}
