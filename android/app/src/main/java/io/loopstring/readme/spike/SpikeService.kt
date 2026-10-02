package io.loopstring.readme.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import org.json.JSONObject

/**
 * Spike-branch foreground-service host for the native spikes. Started only by MainActivity
 * (adb cannot start an unexported service). Reports `SPIKE_<KIND> {json}` under [TAG].
 */
class SpikeService : Service() {
  private val handler = Handler(Looper.getMainLooper())
  private var gap: GapProbe? = null
  private var load: SynthLoad? = null
  private var bridge: SpikeBridge? = null
  private var wakeLock: PowerManager.WakeLock? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    sweepCache()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val cmd = intent?.getStringExtra("cmd") ?: "stop"
    goForeground(intent?.getStringExtra("fgs") ?: "media", cmd)
    when (cmd) {
      "gap" -> startGap(intent!!)
      "bridge" -> startBridge(intent!!)
      else -> {
        stopEverything()
        stopSelf()
      }
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    stopEverything()
    super.onDestroy()
  }

  /** WAVs left by a killed run are removed before anything else runs (R-M12 cleanup habit). */
  private fun sweepCache() {
    val stale = cacheDir.listFiles { f -> f.name.startsWith("spike-") && f.name.endsWith(".wav") }.orEmpty()
    stale.forEach { it.delete() }
    report("SWEEP", JSONObject().put("deleted", stale.size))
  }

  private fun goForeground(fgs: String, cmd: String) {
    val nm = getSystemService(NotificationManager::class.java)
    val builder = if (Build.VERSION.SDK_INT >= 26) {
      nm.createNotificationChannel(NotificationChannel(CHANNEL, "Spikes", NotificationManager.IMPORTANCE_LOW))
      Notification.Builder(this, CHANNEL)
    } else {
      @Suppress("DEPRECATION") Notification.Builder(this)
    }
    val n = builder
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle("Read Me spike: $cmd ($fgs)")
        .setOngoing(true)
        .build()
    val type = when {
      fgs == "special" && Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
      Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
      else -> 0
    }
    if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, type) else startForeground(NOTIFICATION_ID, n)
    report("FGS", JSONObject().put("cmd", cmd).put("requested", fgs).put("type", type).put("sdk", Build.VERSION.SDK_INT))
  }

  private fun startGap(intent: Intent) {
    stopEverything()
    val mode = intent.getStringExtra("mode") ?: "single"
    val rate = intent.getFloatExtra("rate", 2.0f)
    val durationMs = intent.getIntExtra("minutes", 10) * 60_000L
    val useWakeLock = intent.getBooleanExtra("wakelock", false)
    val sentences = SpikeText.sentences(assets.open("spike/corpus.txt").bufferedReader().use { it.readText() })
    if (useWakeLock) acquireWakeLock(durationMs + 120_000L)
    if (mode != "single") {
      load = SynthLoad(this, sentences, active = mode == "concurrent" || mode == "synthonly")
    }
    if (mode == "synthonly") {
      handler.postDelayed({
        val l = load
        load = null
        report("RESULT", JSONObject().put("mode", mode).put("load", l?.stopAndReport()))
        releaseWakeLock()
        stopSelf()
      }, durationMs)
      return
    }
    gap = GapProbe(this, sentences, rate, durationMs) { result ->
      handler.post {
        val l = load
        load = null
        gap = null
        result.put("mode", mode).put("wakelock", useWakeLock).put("sentencesAvailable", sentences.size)
        if (l != null) result.put("load", l.stopAndReport())
        report("RESULT", result)
        releaseWakeLock()
        stopSelf()
      }
    }
  }

  private fun startBridge(intent: Intent) {
    stopEverything()
    val token = intent.getStringExtra("token")
    if (token == null || !TOKEN.matches(token)) {
      report("RESULT", JSONObject().put("bridge", "bad-token"))
      return
    }
    try {
      bridge = SpikeBridge(this, token)
      report("RESULT", JSONObject().put("bridge", "listening").put("port", 8787))
    } catch (e: java.net.BindException) {
      report("RESULT", JSONObject().put("bridge", "bind-failed").put("error", e.javaClass.simpleName))
    }
  }

  private fun stopEverything() {
    gap?.cancel()
    gap = null
    load?.let { report("RESULT", JSONObject().put("cancelledLoad", it.stopAndReport())) }
    load = null
    bridge?.close()
    bridge = null
    handler.removeCallbacksAndMessages(null)
    releaseWakeLock()
  }

  private fun acquireWakeLock(timeoutMs: Long) {
    val pm = getSystemService(PowerManager::class.java)
    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReadMe:spike").apply { acquire(timeoutMs) }
  }

  private fun releaseWakeLock() {
    wakeLock?.let { if (it.isHeld) it.release() }
    wakeLock = null
  }

  companion object {
    const val TAG = "ReadMeSpike"
    private const val CHANNEL = "spike"
    private const val NOTIFICATION_ID = 7001
    private val TOKEN = Regex("[0-9a-f]{32}")

    fun report(kind: String, json: JSONObject) {
      Log.i(TAG, "SPIKE_$kind $json")
    }
  }
}
