package io.loopstring.readme.playback

import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * R-M12 (REA-60): whether the service's notification can show. The bridge runs only while it can,
 * because that notification is what tells the user the bridge is on. False when Read Me's
 * notifications are off (on Android 13+ that includes POST_NOTIFICATIONS denied or revoked) or
 * when the user blocked the playback channel. A channel not created yet counts as shown.
 * The platform API, not NotificationManagerCompat: androidx.core is only a transitive dependency.
 */
object NotificationGate {
  fun shown(context: Context): Boolean {
    val nm = context.getSystemService(NotificationManager::class.java) ?: return false
    if (!nm.areNotificationsEnabled()) return false
    if (Build.VERSION.SDK_INT < 26) return true
    val channel = nm.getNotificationChannel(PlaybackService.CHANNEL) ?: return true
    return channel.importance != NotificationManager.IMPORTANCE_NONE
  }
}
