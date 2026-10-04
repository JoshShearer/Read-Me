package io.loopstring.readme.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import io.loopstring.readme.MainActivity
import io.loopstring.readme.R
import io.loopstring.readme.bridge.BridgeFiles
import io.loopstring.readme.bridge.BridgeServer
import io.loopstring.readme.bridge.TtsSynth
import io.loopstring.readme.playback.PlaybackCommands.Route
import io.loopstring.readme.store.Settings
import io.loopstring.readme.store.Store
import java.io.IOException

/**
 * R-M07 and R-M12: the `mediaPlayback` foreground service that owns playback and, when
 * enabled, hosts the bridge (ADR 0005). The queue runs on TTS binder threads and never needs JS. Logs carry ids,
 * counts, offsets and durations only; the title appears in the notification, never in a log
 * (AGENTS.md 1).
 */
class PlaybackService : Service(), PlaybackSink, TtsSpeaker.Callbacks {
  private val main = Handler(Looper.getMainLooper())
  private val claim = MediaButtonClaim(main)
  private lateinit var store: Store
  private lateinit var audio: AudioManager
  private lateinit var speaker: TtsSpeaker
  private var speakerFor: Pair<String?, String?> = null to null
  private lateinit var queue: PlaybackQueue
  private lateinit var session: MediaSession
  private var focusRequest: AudioFocusRequest? = null
  @Volatile private var pausedForFocus = false
  private var wakeLock: PowerManager.WakeLock? = null
  private var noisyRegistered = false
  private var waiting: PlaybackHub.Request? = null
  private var rebinding = false
  // Cleared by a Pause or Stop during the rebind: the user's pause wins over resuming.
  private var resumeAfterRebind = false
  private var pausedAt = -1L
  private val pauseExpired = Runnable { if (!destroyed) syncForeground(queue.snapshot()) }
  private var rebinds = 0
  private val stallTick = object : Runnable {
    override fun run() {
      if (destroyed) return
      queue.checkStall()
      main.postDelayed(this, STALL_TICK_MS)
    }
  }
  private var title = ""
  private var shown: PlaybackSnapshot? = null
  @Volatile private var destroyed = false
  // Written on the main thread; read on TTS binder threads by preemptOnPlay.
  @Volatile private var bridge: BridgeServer? = null
  @Volatile private var synth: TtsSynth? = null
  private var synthFor: Pair<String?, String?> = null to null
  @VisibleForTesting var bridgePreemptsForTest = 0
    private set
  // ADR 0004: playback starting stops a bridge synthesis in flight (the bridge's instance only).
  private val preemptOnPlay: (PlaybackSnapshot) -> Unit = { s ->
    if (s.playing) bridge?.let { it.preempt(); bridgePreemptsForTest++ }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    store = Store.get(this)
    audio = getSystemService(AudioManager::class.java)
    if (Build.VERSION.SDK_INT >= 26) {
      getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW),
      )
    }
    session = MediaSession(this, "ReadMe").apply { setCallback(sessionCallback, main) }
    speaker = TtsSpeaker(this, this, Settings(this).voice)
    speakerFor = choice()
    queue = PlaybackQueue(speaker, this, { SystemClock.elapsedRealtime() }, TtsSpeaker.maxChars())
    PlaybackHub.queue = queue
    PlaybackHub.controller = ::handle
    PlaybackHub.engine = speaker.status.wire
    val swept = BridgeFiles(cacheDir).sweep()
    if (swept > 0) Log.i(TAG, "bridge swept files=$swept")
    PlaybackHub.addListener(preemptOnPlay)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // startForegroundService obliges startForeground within seconds, whatever happens next.
    goForeground(queue.snapshot())
    when (PlaybackCommands.route(intent?.action, PlaybackHub.hasPending(), queue.snapshot().itemId != null, bridge != null)) {
      Route.START -> PlaybackHub.take()?.let(::begin)
      Route.CONTROL -> handle(intent!!.action!!)
      Route.BRIDGE -> {
        if (intent?.action == PlaybackCommands.ACTION_BRIDGE_OFF) Settings(this).bridgeEnabled = false
        syncBridge()
        // A play request waiting for the engine counts as an item.
        val hasItem = queue.snapshot().itemId != null || waiting != null || PlaybackHub.hasPending()
        if (!ServiceLife.keepAlive(hasItem, bridge != null)) {
          end()
          return START_NOT_STICKY
        }
      }
      Route.IGNORE -> {}
      Route.STOP -> {
        end()
        return START_NOT_STICKY
      }
    }
    // A control that changed nothing (or left playback paused) must not keep the service in
    // the foreground; the queue's own snapshot posts run first, so this sees the final state.
    main.post { if (!destroyed && waiting == null) syncForeground(queue.snapshot()) }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    destroyed = true
    queue.pause() // saves the position if it was playing
    logStats()
    main.removeCallbacksAndMessages(null)
    releasePlayingResources(abandon = true)
    PlaybackHub.controller = null
    PlaybackHub.queue = null
    PlaybackHub.publish(queue.snapshot().copy(playing = false))
    claim.release()
    stopForeground(STOP_FOREGROUND_REMOVE)
    getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    session.release()
    PlaybackHub.removeListener(preemptOnPlay)
    bridge?.close()
    bridge = null
    synth?.shutdown()
    synth = null
    if (PlaybackHub.bridge.state == "on") PlaybackHub.publishBridge(BridgeStatus.OFF)
    speaker.shutdown()
    super.onDestroy()
  }

  // --- requests and controls ---

  /** The engine and voice Settings asks for; the speaker is rebuilt when they change. */
  private fun choice(): Pair<String?, String?> = Settings(this).let { it.engine to it.voice }

  private fun begin(r: PlaybackHub.Request) {
    // A voice or engine chosen in Settings since this speaker was made applies from this play:
    // the service can outlive many plays (a paused hold, the bridge), so onCreate is not enough.
    val wanted = choice()
    if (wanted != speakerFor && speaker.status != TtsSpeaker.EngineStatus.PENDING && !rebinding) {
      speaker.shutdown()
      speaker = TtsSpeaker(this, this, wanted.second)
      speakerFor = wanted
      queue.swapSpeaker(speaker)
      waiting = r
      return
    }
    when (speaker.status) {
      TtsSpeaker.EngineStatus.PENDING -> waiting = r
      TtsSpeaker.EngineStatus.READY -> load(r)
      else -> end()
    }
  }

  private fun load(r: PlaybackHub.Request) {
    // Deleted while the engine was starting (the request waited in `waiting`).
    if (store.item(r.itemId) == null) {
      end()
      return
    }
    title = r.title
    val rate = Settings(this).rate
    rebinds = 0
    if (queue.load(r.itemId, r.sentences, r.startIndex, rate)) {
      Log.i(TAG, "playback start item=${r.itemId} sentences=${r.sentences.size} from=${r.startIndex} rate=$rate")
    } else {
      end()
    }
  }

  /** Every control path (JS, notification, media session, noisy, focus) comes through here. */
  private fun handle(action: String): Boolean = when (action) {
    PlaybackCommands.ACTION_PAUSE -> {
      resumeAfterRebind = false
      val heldForFocus = pausedForFocus
      pausedForFocus = false
      // Already paused by a focus loss: no snapshot follows, so release what that held here,
      // and start ADR 0009's window as a user pause would.
      queue.pause() || heldForFocus.also {
        if (it) main.post { if (!destroyed) { startPauseWindow(); releaseHeld() } }
      }
    }
    PlaybackCommands.ACTION_PLAY -> {
      rebinds = 0
      // REA-33: Play on a paused item resumed with the engine and voice it had, so a change in
      // Settings never reached it. Rebind with the new choice and resume once it is ready.
      if (choice() != speakerFor && !rebinding && !queue.snapshot().playing) follow() else queue.resume()
    }
    PlaybackCommands.ACTION_TOGGLE ->
      if (queue.snapshot().playing) {
        handle(PlaybackCommands.ACTION_PAUSE)
      } else {
        handle(PlaybackCommands.ACTION_PLAY)
      }
    PlaybackCommands.ACTION_NEXT -> queue.next()
    PlaybackCommands.ACTION_PREVIOUS -> queue.previous()
    PlaybackCommands.ACTION_BACK_PARAGRAPH -> queue.backParagraph()
    PlaybackCommands.ACTION_STOP -> {
      resumeAfterRebind = false
      queue.stop()
      true
    }
    else -> false
  }

  /** R-M12: the bridge runs exactly while Settings says so, on the port Settings names. */
  private fun syncBridge() {
    val s = Settings(this)
    val want = s.bridgeEnabled
    bridge?.let { running ->
      if (!want || running.port != s.bridgePort) {
        running.close()
        bridge = null
        synth?.shutdown()
        synth = null
        PlaybackHub.publishBridge(BridgeStatus.OFF)
        Log.i(TAG, "bridge off")
      }
    }
    // REA-33: a voice or engine chosen in Settings reaches the running bridge.
    if (want && bridge != null && choice() != synthFor) {
      synthFor = choice()
      synth?.reselect(synthFor.second)
      Log.i(TAG, "bridge voice changed")
    }
    if (!want || bridge != null) return
    synthFor = choice()
    val sy = TtsSynth(this, s.voice, log = { Log.i(TAG, it) })
    try {
      bridge = BridgeServer(
        s.bridgePort,
        token = { Settings(this).bridgeToken() },
        busy = { PlaybackHub.speaking },
        synth = sy,
        files = BridgeFiles(cacheDir),
        log = { Log.i(TAG, it) },
      )
      synth = sy
      PlaybackHub.publishBridge(BridgeStatus("on", s.bridgePort, null))
      Log.i(TAG, "bridge on port=${s.bridgePort}")
    } catch (e: IOException) {
      sy.shutdown()
      PlaybackHub.publishBridge(BridgeStatus("failed", s.bridgePort, e.javaClass.simpleName))
      Log.i(TAG, "bridge failed: ${e.javaClass.simpleName}")
    }
  }

  // --- TtsSpeaker.Callbacks ---

  override fun onReady(status: TtsSpeaker.EngineStatus) {
    if (rebinding) {
      rebinding = false
      PlaybackHub.engine = status.wire
      if (status == TtsSpeaker.EngineStatus.READY) {
        Log.i(TAG, "playback engine rebound")
        // A play request made while rebinding wins over resuming what was lost.
        val r = waiting
        waiting = null
        if (r != null) load(r) else if (resumeAfterRebind) queue.resume()
      } else {
        Log.i(TAG, "playback engine ${status.wire}")
      }
      return
    }
    PlaybackHub.engine = status.wire
    val r = waiting
    waiting = null
    if (status == TtsSpeaker.EngineStatus.READY) {
      if (r != null) load(r)
    } else {
      Log.i(TAG, "playback engine ${status.wire}")
      PlaybackHub.publish(queue.snapshot())
      end()
    }
  }

  override fun onStart(id: String) = queue.onStart(id)
  override fun onDone(id: String) = queue.onDone(id)
  override fun onError(id: String) = queue.onError(id)

  // --- PlaybackSink (queue lock held; no calls back into the queue) ---

  override fun savePosition(itemId: Long, paragraphIndex: Int, charOffset: Int) {
    store.savePosition(itemId, paragraphIndex, charOffset, System.currentTimeMillis())
  }

  override fun finished(itemId: Long) {
    store.finishReading(itemId, System.currentTimeMillis())
    Log.i(TAG, "playback finished item=$itemId")
  }

  override fun engineLost() {
    main.post { if (!destroyed) recoverEngine() }
  }

  override fun changed(snapshot: PlaybackSnapshot) {
    PlaybackHub.publish(snapshot)
    main.post { if (!destroyed) onSnapshot(snapshot) }
  }

  // --- main thread ---

  private fun onSnapshot(s: PlaybackSnapshot) {
    val before = shown
    shown = s
    if (s.itemId == null) {
      logStats()
      end()
      return
    }
    if (s.playing && before?.playing != true) {
      if (before?.itemId == s.itemId) Log.i(TAG, "playback resumed item=${s.itemId}")
      if (!startPlaying()) return
    } else if (!s.playing && before?.playing == true) {
      Log.i(TAG, "playback paused item=${s.itemId} paragraph=${s.sentence?.paragraphIndex} offset=${s.sentence?.start}")
      logStats()
      val hold = PausePolicy.hold(pausedForFocus)
      releasePlayingResources(abandon = !hold.focus, keepNoisy = hold.noisy)
      if (!pausedForFocus) startPauseWindow()
    }
    updateSession(s)
    syncForeground(s)
  }

  /** REA-33: a new speaker for Settings' engine and voice; onReady resumes where it stopped. */
  private fun follow(): Boolean {
    speakerFor = choice()
    speaker.shutdown()
    speaker = TtsSpeaker(this, this, speakerFor.second)
    queue.swapSpeaker(speaker)
    rebinding = true
    resumeAfterRebind = true
    Log.i(TAG, "playback voice changed")
    return true
  }

  /** REA-18: rebind playback's own engine (never the bridge's) and resume where it stopped. */
  private fun recoverEngine() {
    if (!RecoveryPolicy.rebind(rebinds, rebinding)) {
      if (!rebinding) Log.i(TAG, "playback engine lost; paused")
      return
    }
    rebinds++
    rebinding = true
    resumeAfterRebind = true
    Log.i(TAG, "playback engine lost; rebinding")
    speaker.shutdown()
    speakerFor = choice()
    speaker = TtsSpeaker(this, this, speakerFor.second)
    queue.swapSpeaker(speaker)
  }

  /** False when playback could not be granted focus or the foreground, and was paused. */
  private fun startPlaying(): Boolean {
    pausedAt = -1L
    main.removeCallbacks(pauseExpired)
    pausedForFocus = false
    if (!requestFocus()) {
      queue.pause()
      return false
    }
    claim.claim()
    main.removeCallbacks(stallTick)
    main.postDelayed(stallTick, STALL_TICK_MS)
    if (wakeLock == null) {
      // SPIKE-05 measured gaps on USB power without a wake lock; battery and Doze were never
      // measured, so playback holds one while speaking (device:gap measures it).
      wakeLock = getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReadMe:playback")
        .apply { setReferenceCounted(false); acquire(WAKE_LOCK_MS) }
    }
    if (!noisyRegistered) {
      val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
      if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, filter, RECEIVER_NOT_EXPORTED)
      else registerReceiver(noisy, filter)
      noisyRegistered = true
    }
    return true
  }

  /** ADR 0009: a user pause keeps the foreground for PauseWindow.HOLD_MS. */
  private fun startPauseWindow() {
    pausedAt = SystemClock.elapsedRealtime()
    main.removeCallbacks(pauseExpired)
    main.postDelayed(pauseExpired, PauseWindow.HOLD_MS)
  }

  private fun releaseHeld() {
    releasePlayingResources(abandon = true)
    syncForeground(queue.snapshot())
  }

  private fun releasePlayingResources(abandon: Boolean, keepNoisy: Boolean = false) {
    main.removeCallbacks(stallTick)
    wakeLock?.let { if (it.isHeld) it.release() }
    wakeLock = null
    if (noisyRegistered && !keepNoisy) {
      unregisterReceiver(noisy)
      noisyRegistered = false
    }
    if (abandon) abandonFocus()
  }

  private fun syncForeground(s: PlaybackSnapshot) {
    val bridgeOn = bridge != null
    if (s.itemId == null && !bridgeOn) return
    val held = PauseWindow.held(pausedAt, SystemClock.elapsedRealtime())
    if (ServiceLife.foreground(s.playing, pausedForFocus, bridgeOn, held)) {
      goForeground(s)
    } else {
      // Paused for longer than ADR 0009's window: the notification stays (with Play) but can be
      // swiped away, and the system may stop the service; the position is already saved.
      stopForeground(STOP_FOREGROUND_DETACH)
      getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(s))
    }
  }

  private fun goForeground(s: PlaybackSnapshot) {
    val n = notification(s)
    try {
      if (Build.VERSION.SDK_INT >= 29) {
        startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
      } else {
        startForeground(NOTIFICATION_ID, n)
      }
    } catch (e: IllegalStateException) {
      // Android 12+ refuses a foreground start from the background outside the exemptions
      // (notification action, media button). Stay paused rather than speak unprotected.
      Log.i(TAG, "playback resume refused: ${e.javaClass.simpleName}")
      queue.pause()
    }
  }

  private fun end() {
    main.removeCallbacks(pauseExpired)
    pausedAt = -1L
    waiting = null
    releasePlayingResources(abandon = true)
    session.isActive = false
    // R-M12: an enabled bridge keeps the service, and its notification, after playback ends.
    if (bridge != null) {
      goForeground(queue.snapshot())
      return
    }
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun logStats() {
    val st = queue.takeStats()
    val g = st.gaps
    if (g.count == 0 && st.errors == 0 && st.saveErrors == 0) return
    Log.i(TAG, "playback gaps n=${g.count} p50=${g.p50} p95=${g.p95} max=${g.max} stalls=${g.stalls} errors=${st.errors} saveErrors=${st.saveErrors}")
  }

  // --- focus, noisy, session, notification ---

  private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
    when (FocusPolicy.onChange(change, pausedForFocus)) {
      FocusAction.PAUSE -> handle(PlaybackCommands.ACTION_PAUSE)
      FocusAction.PAUSE_TRANSIENT -> if (queue.snapshot().playing) {
        pausedForFocus = true
        queue.pause()
      }
      FocusAction.RESUME -> {
        pausedForFocus = false
        queue.resume()
      }
      FocusAction.NONE -> {}
    }
  }

  private fun requestFocus(): Boolean {
    val result = if (Build.VERSION.SDK_INT >= 26) {
      val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(TtsSpeaker.ATTRIBUTES)
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener(focusListener, main)
        .build()
        .also { focusRequest = it }
      audio.requestAudioFocus(req)
    } else {
      @Suppress("DEPRECATION")
      audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
    }
    return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
  }

  private fun abandonFocus() {
    if (Build.VERSION.SDK_INT >= 26) {
      focusRequest?.let { audio.abandonAudioFocusRequest(it) }
    } else {
      @Suppress("DEPRECATION")
      audio.abandonAudioFocus(focusListener)
    }
  }

  private val noisy = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) handle(PlaybackCommands.ACTION_PAUSE)
    }
  }

  private val sessionCallback = object : MediaSession.Callback() {
    override fun onPlay() { handle(PlaybackCommands.ACTION_PLAY) }
    override fun onPause() { handle(PlaybackCommands.ACTION_PAUSE) }
    override fun onSkipToNext() { handle(PlaybackCommands.ACTION_NEXT) }
    override fun onSkipToPrevious() { handle(PlaybackCommands.ACTION_PREVIOUS) }
    override fun onStop() { handle(PlaybackCommands.ACTION_STOP) }
  }

  private fun updateSession(s: PlaybackSnapshot) {
    session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title).build())
    session.setPlaybackState(
      PlaybackState.Builder()
        .setActions(
          PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP,
        )
        .setState(
          if (s.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
          PlaybackState.PLAYBACK_POSITION_UNKNOWN,
          if (s.playing) 1f else 0f,
        )
        .build(),
    )
    session.isActive = true
  }

  private fun notification(s: PlaybackSnapshot): Notification {
    val b = if (Build.VERSION.SDK_INT >= 26) {
      Notification.Builder(this, CHANNEL)
    } else {
      @Suppress("DEPRECATION") Notification.Builder(this)
    }
    val toggle = if (s.playing) {
      action(android.R.drawable.ic_media_pause, "Pause", PlaybackCommands.ACTION_PAUSE, 2)
    } else {
      action(android.R.drawable.ic_media_play, "Play", PlaybackCommands.ACTION_PLAY, 2)
    }
    val open = PendingIntent.getActivity(
      this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )
    val bridgeOn = bridge != null
    val text = ServiceText.of(title, s, bridgeOn)
    b.setSmallIcon(R.drawable.ic_stat_readme)
      .setContentTitle(text.title)
      .setContentText(text.body)
      .setContentIntent(open)
      .setOngoing(s.playing || bridgeOn)
    if (text.media) {
      b.addAction(action(android.R.drawable.ic_media_previous, "Previous", PlaybackCommands.ACTION_PREVIOUS, 1))
        .addAction(toggle)
        .addAction(action(android.R.drawable.ic_media_next, "Next", PlaybackCommands.ACTION_NEXT, 3))
        .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
    }
    if (bridgeOn) b.addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Turn off bridge", PlaybackCommands.ACTION_BRIDGE_OFF, 4))
    return b.build()
  }

  private fun action(icon: Int, label: String, act: String, code: Int): Notification.Action {
    val i = Intent(this, PlaybackService::class.java).setAction(act)
    val pi = if (Build.VERSION.SDK_INT >= 26) {
      PendingIntent.getForegroundService(this, code, i, PendingIntent.FLAG_IMMUTABLE)
    } else {
      PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE)
    }
    return Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build()
  }

  companion object {
    private const val TAG = "ReadMe"
    private const val CHANNEL = "playback"
    const val NOTIFICATION_ID = 3001
    private const val WAKE_LOCK_MS = 4 * 60 * 60 * 1000L
    private const val STALL_TICK_MS = 5_000L

    /** Called from ReadMeSpeech while the app is in the foreground (the user tapped play). */
    fun start(context: Context, request: PlaybackHub.Request) {
      PlaybackHub.offer(request)
      val i = Intent(context, PlaybackService::class.java).setAction(PlaybackCommands.ACTION_START)
      if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
    }

    /**
     * R-M12: makes the running service match Settings, starting it when the bridge is on. Call
     * only from the foreground (Settings, MainActivity.onResume): Android 12+ refuses a
     * foreground-service start from the background.
     */
    fun syncBridge(context: Context) {
      val on = Settings(context).bridgeEnabled
      val alive = PlaybackHub.controller != null
      if (!on && !alive) {
        PlaybackHub.publishBridge(BridgeStatus.OFF)
        return
      }
      val i = Intent(context, PlaybackService::class.java).setAction(PlaybackCommands.ACTION_BRIDGE)
      if (alive || Build.VERSION.SDK_INT < 26) context.startService(i) else context.startForegroundService(i)
    }
  }
}
