package io.loopstring.readme.bridge

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle

/** What Settings shows for the bridge: Settings says whether it should run, the service whether it does. */
object BridgeView {
  fun state(enabled: Boolean, hub: String): String = when {
    !enabled -> "off"
    hub == "on" || hub == "failed" -> hub
    else -> "starting"
  }
}

/** R-M12 "shown in Settings with a Copy button". Marked sensitive so Android 13+ hides it from the clipboard preview. */
object TokenClipboard {
  fun copy(context: Context, token: String) {
    val clip = ClipData.newPlainText("Read Me bridge token", token)
    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
  }
}
