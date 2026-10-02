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
import io.loopstring.readme.MainActivity
import io.loopstring.readme.playback.PlaybackCommands.Route
import io.loopstring.readme.store.Settings
import io.loopstring.readme.store.Store

/**
 * R-M07: the `mediaPlayback` foreground service that owns playback (ADR 0005; Phase 5's bridge
 * moves in later). The queue runs on TTS binder threads and never needs JS. Logs carry ids,
 * counts, offsets and durations only; the title appears in the notification, never in a log
 * (AGENTS.md 1).
 */
class PlaybackService : Service(), PlaybackSink, TtsSpeaker.Callbacks {
  private val main = Handler(Looper.getMainLooper())
  private lateinit var store: Store
  private lateinit var audio: AudioManager
  private lateinit var speaker: TtsSpeaker
  private lateinit var queue: PlaybackQueue
  private lateinit var session: MediaSession
  private var focusRequest: AudioFocusRequest? = null
  @Volatile private var pausedForFocus = false
  private var wakeLock: PowerManager.WakeLock? = null
  private var noisyRegistered = false
  private var waiting: PlaybackHub.Request? = null
  private var title = ""
  private var shown: PlaybackSnapshot? = null
  @Volatile private var destroyed = false

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
    speaker = TtsSpeaker(this, this)
    queue = PlaybackQueue(speaker, this, { SystemClock.elapsedRealtime() }, TtsSpeaker.maxChars())
    PlaybackHub.queue = queue
    PlaybackHub.controller = ::handle
    PlaybackHub.engine = speaker.status.wire
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // startForegroundService obliges startForeground within seconds, whatever happens next.
    goForeground(queue.snapshot())
    when (PlaybackCommands.route(intent?.action, PlaybackHub.hasPending(), queue.snapshot().itemId != null)) {
      Route.START -> PlaybackHub.take()?.let(::begin)
      Route.CONTROL -> handle(intent!!.action!!)
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
    session.release()
    speaker.shutdown()
    super.onDestroy()
  }

  // --- requests and controls ---

  private fun begin(r: PlaybackHub.Request) {
    when (speaker.status) {
      TtsSpeaker.EngineStatus.PENDING -> waiting = r
      TtsSpeaker.EngineStatus.READY -> load(r)
      else -> end()
    }
  }

  private fun load(r: PlaybackHub.Request) {
    title = r.title
    val rate = Settings(this).rate
    if (queue.load(r.itemId, r.sentences, r.startIndex, rate)) {
      Log.i(TAG, "playback start item=${r.itemId} sentences=${r.sentences.size} from=${r.startIndex} rate=$rate")
    } else {
      end()
    }
  }

  /** Every control path (JS, notification, media session, noisy, focus) comes through here. */
  private fun handle(action: String): Boolean = when (action) {
    PlaybackCommands.ACTION_PAUSE -> {
      pausedForFocus = false
      queue.pause()
    }
    PlaybackCommands.ACTION_PLAY -> queue.resume()
    PlaybackCommands.ACTION_TOGGLE ->
      if (queue.snapshot().playing) handle(PlaybackCommands.ACTION_PAUSE) else queue.resume()
    PlaybackCommands.ACTION_NEXT -> queue.next()
    PlaybackCommands.ACTION_PREVIOUS -> queue.previous()
    PlaybackCommands.ACTION_BACK_PARAGRAPH -> queue.backParagraph()
    PlaybackCommands.ACTION_STOP -> {
      queue.stop()
      true
    }
    else -> false
  }

  // --- TtsSpeaker.Callbacks ---

  override fun onReady(status: TtsSpeaker.EngineStatus) {
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
      releasePlayingResources(abandon = !pausedForFocus)
    }
    updateSession(s)
    syncForeground(s)
  }

  /** False when playback could not be granted focus or the foreground, and was paused. */
  private fun startPlaying(): Boolean {
    pausedForFocus = false
    if (!requestFocus()) {
      queue.pause()
      return false
    }
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

  private fun releasePlayingResources(abandon: Boolean) {
    wakeLock?.let { if (it.isHeld) it.release() }
    wakeLock = null
    if (noisyRegistered) {
      unregisterReceiver(noisy)
      noisyRegistered = false
    }
    if (abandon) abandonFocus()
  }

  private fun syncForeground(s: PlaybackSnapshot) {
    if (s.itemId == null) return
    if (s.playing) {
      goForeground(s)
    } else {
      // Paused: the notification stays (with Play) but can be swiped away, and the system may
      // stop the service; the position is already saved.
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
    waiting = null
    releasePlayingResources(abandon = true)
    session.isActive = false
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun logStats() {
    val st = queue.takeStats()
    val g = st.gaps
    if (g.count == 0 && st.errors == 0) return
    Log.i(TAG, "playback gaps n=${g.count} p50=${g.p50} p95=${g.p95} max=${g.max} stalls=${g.stalls} errors=${st.errors}")
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
    return b.setSmallIcon(android.R.drawable.ic_media_play)
      .setContentTitle(title)
      .setContentText(if (s.playing) "Reading" else "Paused")
      .setContentIntent(open)
      .setOngoing(s.playing)
      .addAction(action(android.R.drawable.ic_media_previous, "Previous", PlaybackCommands.ACTION_PREVIOUS, 1))
      .addAction(toggle)
      .addAction(action(android.R.drawable.ic_media_next, "Next", PlaybackCommands.ACTION_NEXT, 3))
      .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
      .build()
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
    private const val NOTIFICATION_ID = 3001
    private const val WAKE_LOCK_MS = 4 * 60 * 60 * 1000L

    /** Called from ReadMeSpeech while the app is in the foreground (the user tapped play). */
    fun start(context: Context, request: PlaybackHub.Request) {
      PlaybackHub.offer(request)
      val i = Intent(context, PlaybackService::class.java).setAction(PlaybackCommands.ACTION_START)
      if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
    }
  }
}
