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
import io.loopstring.readme.bridge.BridgePlayback
import io.loopstring.readme.bridge.BridgeServer
import io.loopstring.readme.bridge.BridgeView
import io.loopstring.readme.bridge.TtsSynth
import io.loopstring.readme.playback.PlaybackCommands.Route
import io.loopstring.readme.store.Settings
import io.loopstring.readme.store.Store
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * R-M07 and R-M12: the `mediaPlayback` foreground service that owns playback and, when
 * enabled, hosts the bridge (ADR 0005). The queue runs on TTS binder threads and the player's
 * thread, and never needs JS. Logs carry ids, counts, offsets and durations only; the title
 * appears in the notification, never in a log (AGENTS.md 1).
 *
 * REA-31 (ADR 0012): the engine synthesizes sentences to app-private files and [TrackPlayer]
 * plays them, so the audio, the audio focus and the media keys are Read Me's for every engine.
 */
class PlaybackService : Service(), PlaybackSink, TtsSpeaker.Callbacks, PlayerEvents {
  private val main = Handler(Looper.getMainLooper())
  private lateinit var store: Store
  private lateinit var files: PlaybackFiles
  private lateinit var player: TrackPlayer
  private lateinit var audio: AudioManager
  // Written on the main thread only (REA-18: follow() is posted there); read by control() on
  // the JS thread.
  @Volatile private lateinit var speaker: TtsSpeaker
  @Volatile private var speakerFor: Pair<String?, String?> = null to null
  private lateinit var queue: PlaybackQueue
  private lateinit var session: MediaSession
  private var focusRequest: AudioFocusRequest? = null
  @Volatile private var pausedForFocus = false
  private var wakeLock: PowerManager.WakeLock? = null
  private var wakeAcquiredAt = 0L
  private var noisyRegistered = false
  // A play request held while the engine starts. Read on the JS thread by handle().
  @Volatile private var waiting: PlaybackHub.Request? = null
  // REA-35: end() stops with the newest start id, so a start Android delivered since survives.
  private var lastStartId = 0
  // These three are written by control() on the JS thread as well as on main.
  @Volatile private var rebinding = false
  // Cleared by a Pause or Stop during the rebind: the user's pause wins over resuming.
  @Volatile private var resumeAfterRebind = false
  // REA-18: counts user Pauses and Stops. Whatever asks for a rebind (a loss the queue found, a
  // user Play) takes the count first; the rebind resumes only if it has not moved since, however
  // the request and the Pause were ordered across threads.
  private val userStops = java.util.concurrent.atomic.AtomicInteger()
  // The userStops count taken by the request behind the current rebind.
  @Volatile private var rebindMark = 0
  private var pausedAt = -1L
  private val pauseExpired = Runnable {
    if (destroyed) return@Runnable
    // The service may be stopped from here on: keep the saved position, not the audio files.
    queue.dropPrefetch()
    syncForeground(queue.snapshot())
  }
  @Volatile private var rebinds = 0
  private lateinit var prepareWorker: ExecutorService
  private val stallTick = object : Runnable {
    override fun run() {
      if (destroyed) return
      queue.checkStall()
      renewWakeLock()
      main.postDelayed(this, STALL_TICK_MS)
    }
  }
  private var title = ""
  // R-S05: the title of the item nextItem offered (the player's or a TTS binder thread). It
  // becomes [title] only when a snapshot of that item reaches the main thread, so an earlier
  // item's snapshot never shows it and an offer the queue dropped never does (ADR 0011).
  @Volatile private var offered: Pair<Long, String>? = null
  private var shown: PlaybackSnapshot? = null
  // REA-18: what the posted notification shows; a snapshot that changes none of it (a new
  // sentence) posts nothing.
  private var posted: ServiceText.Shown? = null
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
    files = PlaybackFiles(File(noBackupFilesDir, "playback"))
    val stale = files.sweep()
    if (stale > 0) Log.i(TAG, "playback swept files=$stale")
    player = TrackPlayer(this, TrackPlayer::track, log = { Log.i(TAG, it) })
    player.start()
    speaker = TtsSpeaker(this, this, Settings(this).voice)
    speakerFor = choice()
    // REA-49: the next item is built for preparation on this one thread, never on the main, JS,
    // binder or player thread (resume() is called from main and JS).
    prepareWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "ReadMePrepare").apply { isDaemon = true } }
    val worker = prepareWorker
    queue = PlaybackQueue(
      speaker, player, files, this, { SystemClock.elapsedRealtime() }, TtsSpeaker.maxChars(),
      prepareOn = { worker.execute(it) },
    )
    PlaybackHub.queue = queue
    PlaybackHub.controller = ::handle
    PlaybackHub.engine = speaker.status.wire
    val swept = BridgeFiles(cacheDir).sweep()
    if (swept > 0) Log.i(TAG, "bridge swept files=$swept")
    PlaybackHub.addListener(preemptOnPlay)
    // REA-60: the user blocking Read Me's notifications or its channel (Android 9+) stops the
    // bridge at once. A permission revoke sends neither; on the reference phone it killed the
    // process, and the next open refuses the restart (MainActivity.onResume).
    if (Build.VERSION.SDK_INT >= 28) {
      val filter = IntentFilter(NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED).apply {
        addAction(NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED)
      }
      if (Build.VERSION.SDK_INT >= 33) registerReceiver(blockState, filter, RECEIVER_NOT_EXPORTED)
      else registerReceiver(blockState, filter)
    }
  }

  private val blockState = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (destroyed) return
      syncBridge()
      if (!ServiceLife.keepAlive(hasItem(), bridge != null)) end()
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    lastStartId = startId
    // startForegroundService obliges startForeground within seconds, whatever happens next.
    goForeground(queue.snapshot())
    // REA-35: a play request waiting for the engine counts as an item, so a stale control intent
    // during a cold start is handled (and mostly ignored) instead of stopping the service.
    when (PlaybackCommands.route(intent?.action, PlaybackHub.hasPending(), hasItem(), bridge != null)) {
      // Claimed, not taken: until begin() has held or loaded it, a Pause from the JS thread
      // must still find it (REA-35 critique).
      Route.START -> PlaybackHub.claim()?.let { r ->
        try { begin(r) } finally { PlaybackHub.release() }
      }
      Route.CONTROL -> handle(intent!!.action!!)
      Route.BRIDGE -> {
        if (intent?.action == PlaybackCommands.ACTION_BRIDGE_OFF) Settings(this).bridgeEnabled = false
        syncBridge()
        if (!ServiceLife.keepAlive(hasItem(), bridge != null)) {
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
    // Paused above; the item stays (JS shows it paused), its audio files do not (AGENTS.md 3).
    queue.dropPrefetch()
    player.release()
    // A preparation still building is dropped (its execute is refused from here on); the sweep
    // below removes any prepared file.
    prepareWorker.shutdownNow()
    files.sweep()
    stopForeground(STOP_FOREGROUND_REMOVE)
    getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    session.release()
    PlaybackHub.removeListener(preemptOnPlay)
    if (Build.VERSION.SDK_INT >= 28) runCatching { unregisterReceiver(blockState) }
    bridge?.close()
    bridge = null
    synth?.shutdown()
    synth = null
    if (PlaybackHub.bridge.state == "on") PlaybackHub.publishBridge(BridgeStatus.OFF)
    speaker.shutdown()
    super.onDestroy()
  }

  // --- requests and controls ---

  /** A play request waiting for the engine, or offered and not yet started, counts as an item. */
  private fun hasItem() = queue.snapshot().itemId != null || startingItemId() != null

  // The hub is read first: begin() sets `waiting` before releasing its claim, so a reader that
  // finds the claim gone is guaranteed to see `waiting`.
  private fun startingItemId(): Long? = PlaybackHub.startingItemId() ?: waiting?.itemId

  /**
   * The queue's state plus the request waiting for the engine, so JS can reach it (REA-35 #4).
   * A claimed request is left out: it is either held (then `waiting`) or loaded by now.
   */
  private fun withWaiting(s: PlaybackSnapshot) = s.copy(waitingItemId = waiting?.itemId ?: PlaybackHub.pendingItemId())

  // Under the queue's lock, as the queue's own changed() is: an unlocked read-then-publish could
  // land after a handover's snapshot and move the Reader back to the finished item (REA-55).
  private fun publishState() = queue.publishWith { PlaybackHub.publish(withWaiting(it)) }

  private fun hold(r: PlaybackHub.Request) {
    waiting = r
    Log.i(TAG, "playback waiting item=${r.itemId}")
    publishState()
  }

  /** The engine and voice Settings asks for; the speaker is rebuilt when they change. */
  private fun choice(): Pair<String?, String?> = Settings(this).let { it.engine to it.voice }

  private fun begin(r: PlaybackHub.Request) {
    // A voice or engine chosen in Settings since this speaker was made applies from this play:
    // the service can outlive many plays (a paused hold, the bridge), so onCreate is not enough.
    // REA-35 #3: a speaker whose engine failed (no engine, no voice) never recovers by itself,
    // so each user Play on it binds a fresh one, once.
    val wanted = choice()
    val failed = speaker.status == TtsSpeaker.EngineStatus.NO_ENGINE || speaker.status == TtsSpeaker.EngineStatus.NO_VOICE
    if ((wanted != speakerFor || failed) && speaker.status != TtsSpeaker.EngineStatus.PENDING && !rebinding) {
      // REA-51: the old speaker is shut down only after the queue has left its generation.
      val old = speaker
      speaker = TtsSpeaker(this, this, wanted.second)
      speakerFor = wanted
      queue.swapSynth(speaker) { old.shutdown() }
      hold(r)
      return
    }
    when (speaker.status) {
      TtsSpeaker.EngineStatus.PENDING -> hold(r)
      TtsSpeaker.EngineStatus.READY -> load(r)
      else -> end()
    }
  }

  private fun load(r: PlaybackHub.Request) {
    // Deleted while the engine was starting (the request waited in `waiting`). A bridge session
    // (REA-39) is not in the store.
    if (r.itemId != BRIDGE_ITEM && store.item(r.itemId) == null) {
      end()
      return
    }
    title = r.title
    val rate = r.rate ?: Settings(this).rate
    rebinds = 0
    if (queue.load(r.itemId, r.sentences, r.startIndex, rate)) {
      Log.i(TAG, "playback start item=${r.itemId} sentences=${r.sentences.size} from=${r.startIndex} rate=$rate")
    } else {
      end()
    }
  }

  /**
   * Every control path (JS, notification, media session, noisy, focus) comes through here. JS
   * calls it on its own thread. REA-35: while a play request waits for the engine, Pause and
   * Stop cancel it; any other control is ignored, since the request will replace the item.
   */
  private fun handle(action: String): Boolean {
    if (startingItemId() == null) return control(action)
    if (action != PlaybackCommands.ACTION_PAUSE && action != PlaybackCommands.ACTION_STOP) return false
    // Taken here, on the caller's thread, so a Play offered after this Pause is not taken too.
    val pending = PlaybackHub.take()
    onMain {
      cancelStart(pending)
      control(action)
    }
    return true
  }

  private fun onMain(block: () -> Unit) {
    if (Looper.myLooper() == main.looper) block() else main.post { if (!destroyed) block() }
  }

  private fun cancelStart(pending: PlaybackHub.Request?) {
    val r = waiting ?: pending ?: return
    waiting = null
    resumeAfterRebind = false
    Log.i(TAG, "playback start cancelled item=${r.itemId}")
    publishState()
    if (queue.snapshot().itemId == null) end()
  }

  private fun control(action: String): Boolean = when (action) {
    PlaybackCommands.ACTION_PAUSE -> {
      resumeAfterRebind = false
      val heldForFocus = pausedForFocus
      pausedForFocus = false
      // Already paused by a focus loss: no snapshot follows, so release what that held here,
      // and start ADR 0009's window as a user pause would.
      val changed = queue.pause() || heldForFocus.also {
        if (it) main.post { if (!destroyed) { startPauseWindow(); releaseHeld() } }
      }
      // After the queue call, never before: pause() takes the queue lock, so a loss the queue
      // reported before it (engineLost, under that lock) saw the old count and its rebind will
      // not resume; one after it found nothing playing (REA-18).
      userStops.incrementAndGet()
      changed
    }
    PlaybackCommands.ACTION_PLAY -> {
      rebinds = 0
      // REA-33: Play on a paused item resumed with the engine and voice it had, so a change in
      // Settings never reached it. Rebind with the new choice and resume once it is ready.
      // REA-18: so does Play on a speaker whose engine failed or never answered; that bind is
      // the user's, so it does not count against RecoveryPolicy's one rebind.
      if (needsFreshSpeaker()) {
        // TtsSpeaker is built and `speaker` written on main; control() may be on the JS thread,
        // so a Pause or Stop can arrive before this runs. The mark is taken here, at the Play.
        val mark = userStops.get()
        onMain {
          if (needsFreshSpeaker()) follow(mark) else if (userStops.get() == mark) queue.resume()
        }
        true
      } else {
        queue.resume()
      }
    }
    PlaybackCommands.ACTION_TOGGLE ->
      if (queue.snapshot().playing) {
        control(PlaybackCommands.ACTION_PAUSE)
      } else {
        control(PlaybackCommands.ACTION_PLAY)
      }
    PlaybackCommands.ACTION_NEXT -> queue.next()
    PlaybackCommands.ACTION_PREVIOUS -> queue.previous()
    PlaybackCommands.ACTION_BACK_PARAGRAPH -> queue.backParagraph()
    PlaybackCommands.ACTION_STOP -> {
      resumeAfterRebind = false
      queue.stop()
      // After the queue call, as for Pause.
      userStops.incrementAndGet()
      true
    }
    else -> false
  }

  private fun speakerFailed() =
    speaker.status == TtsSpeaker.EngineStatus.NO_ENGINE || speaker.status == TtsSpeaker.EngineStatus.NO_VOICE

  private fun needsFreshSpeaker() =
    !rebinding && !queue.snapshot().playing && (choice() != speakerFor || speakerFailed())

  /**
   * R-M12: the bridge runs exactly while Settings says so, on the port Settings names, and only
   * while its notification can show (REA-60); blocked, the saved setting stays on.
   */
  private fun syncBridge() {
    val s = Settings(this)
    val blocked = s.bridgeEnabled && !notificationsShown(this)
    val want = BridgeView.allowed(s.bridgeEnabled, !blocked)
    if (blocked) Log.i(TAG, "bridge blocked: notifications off")
    val wasRunning = bridge != null
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
    // REA-60 plan review F3: a bridge session belongs to the plugin, which can no longer reach
    // /control once the bridge is closed; stop it rather than read on with nothing to stop it.
    if (blocked && wasRunning) {
      val ours = queue.snapshot().itemId == BRIDGE_ITEM || startingItemId() == BRIDGE_ITEM
      if (ours) handle(PlaybackCommands.ACTION_STOP)
    }
    // Plan review F5 (REA-23): turned off after a failed bind; without this the hub kept
    // "failed", and turning it back on showed the old failure instead of Starting.
    if (!want && bridge == null && PlaybackHub.bridge.state == "failed") PlaybackHub.publishBridge(BridgeStatus.OFF)
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
        player = bridgePlayer,
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

  // --- REA-39, ADR 0014: the plugin's chunks, read by this service's own playback ---

  // The newest session's number; the session whose last chunk was read; the chunk being read.
  @Volatile private var bridgeSession = 0
  @Volatile private var bridgeEnded = 0
  @Volatile private var bridgeIndex = 0

  /** Bridge worker threads call in; the work runs on main, as a JS Play's begin() does. */
  @VisibleForTesting val bridgePlayer = object : BridgePlayback {
    override fun play(chunks: List<String>, rate: Float): Int? = onMainAndWait {
      // ADR 0004: Read Me's own reading has the engine; a paused item gives way.
      val s = queue.snapshot()
      val own = (s.itemId != null && s.itemId != BRIDGE_ITEM && s.playing) ||
        startingItemId().let { it != null && it != BRIDGE_ITEM }
      if (own) return@onMainAndWait null
      val n = bridgeSession + 1
      bridgeSession = n
      bridgeIndex = 0
      // One row per chunk: paragraphIndex is the plugin's chunk number (Utterances.fit keeps it
      // when it splits a long chunk), so the status index needs no mapping.
      val rows = chunks.mapIndexed { i, t -> SentenceRow(i, 0, t.length, t) }
      Log.i(TAG, "bridge session start session=$n chunks=${chunks.size} rate=$rate")
      begin(PlaybackHub.Request(BRIDGE_ITEM, BRIDGE_TITLE, rows, 0, rate))
      n
    }

    override fun status(): BridgePlayback.Status {
      val n = bridgeSession
      if (n == 0) return BridgePlayback.Status(0, BridgePlayback.State.NONE, 0)
      val s = queue.snapshot()
      val state = when {
        s.itemId == BRIDGE_ITEM -> if (s.playing) BridgePlayback.State.PLAYING else BridgePlayback.State.PAUSED
        startingItemId() == BRIDGE_ITEM -> BridgePlayback.State.STARTING
        bridgeEnded == n -> BridgePlayback.State.ENDED
        else -> BridgePlayback.State.STOPPED
      }
      return BridgePlayback.Status(n, state, bridgeIndex)
    }

    override fun control(session: Int, action: BridgePlayback.Action): BridgePlayback.Status? {
      if (session != bridgeSession) return null
      val ours = queue.snapshot().itemId == BRIDGE_ITEM || startingItemId() == BRIDGE_ITEM
      if (ours) {
        handle(
          when (action) {
            BridgePlayback.Action.PAUSE -> PlaybackCommands.ACTION_PAUSE
            BridgePlayback.Action.RESUME -> PlaybackCommands.ACTION_PLAY
            BridgePlayback.Action.STOP -> PlaybackCommands.ACTION_STOP
          },
        )
      }
      return status()
    }
  }

  /** Runs [block] on main and returns its result; null if main does not get to it in 5 s. */
  private fun <T> onMainAndWait(block: () -> T?): T? {
    if (Looper.myLooper() == main.looper) return block()
    val done = java.util.concurrent.CountDownLatch(1)
    val result = java.util.concurrent.atomic.AtomicReference<T?>(null)
    main.post {
      try { if (!destroyed) result.set(block()) } finally { done.countDown() }
    }
    return if (done.await(5, java.util.concurrent.TimeUnit.SECONDS)) result.get() else null
  }

  // --- TtsSpeaker.Callbacks ---

  override fun onReady(status: TtsSpeaker.EngineStatus) {
    // A late engine answer after onDestroy: the player is released and nothing may start.
    if (destroyed) return
    if (rebinding) {
      rebinding = false
      PlaybackHub.engine = status.wire
      if (status == TtsSpeaker.EngineStatus.READY) {
        Log.i(TAG, "playback engine rebound")
        // A play request made while rebinding wins over resuming what was lost.
        val r = waiting
        waiting = null
        if (r != null) load(r) else if (resumeAfterRebind && userStops.get() == rebindMark) queue.resume()
      } else {
        // REA-35 #3: a request held for this engine is dropped, the engine state reaches JS,
        // and the foreground follows the paused item again; the next Play binds afresh (begin).
        Log.i(TAG, "playback engine ${status.wire}")
        waiting?.let { Log.i(TAG, "playback start cancelled item=${it.itemId}") }
        waiting = null
        resumeAfterRebind = false
        publishState()
        if (queue.snapshot().itemId == null) end() else syncForeground(queue.snapshot())
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
      publishState()
      end()
    }
  }

  /**
   * REA-18: the engine of [s] never answered (a rebind, a voice change or a cold start). Before,
   * `rebinding` or `waiting` stayed set for ever, so later losses were ignored and every request
   * was held. A held request is dropped; an item stays paused at its saved position, with its
   * transport (ADR 0012 D5: nothing starts by itself); with no item the service ends. The
   * engine state published is "pending", not "no-engine": a timeout does not show that this
   * phone has no voice (R-M06's card), and the next Play binds a fresh engine.
   */
  override fun onStartTimedOut(s: TtsSpeaker) {
    if (destroyed || s !== speaker) return
    Log.i(TAG, "playback engine start timed out ms=${TtsSpeaker.START_TIMEOUT_MS}")
    rebinding = false
    resumeAfterRebind = false
    PlaybackHub.engine = TtsSpeaker.EngineStatus.PENDING.wire
    waiting?.let { Log.i(TAG, "playback start cancelled item=${it.itemId}") }
    waiting = null
    publishState()
    if (queue.snapshot().itemId == null) end() else syncForeground(queue.snapshot())
  }

  override fun onSynthDone(id: String) = queue.onSynthDone(id)
  override fun onSynthError(id: String, lost: Boolean) = queue.onSynthError(id, lost)

  // --- PlayerEvents (the player's thread) ---

  override fun onPlayerStart(id: String) = queue.onPlayerStart(id)
  override fun onPlayerDone(id: String) = queue.onPlayerDone(id)
  override fun onPlayerError(id: String) = queue.onPlayerError(id)

  // --- PlaybackSink (queue lock held; no calls back into the queue) ---

  override fun savePosition(itemId: Long, paragraphIndex: Int, charOffset: Int) {
    // REA-39: the plugin's text is never stored; its position is the plugin's to keep.
    if (itemId == BRIDGE_ITEM) return
    store.savePosition(itemId, paragraphIndex, charOffset, System.currentTimeMillis())
  }

  override fun finished(itemId: Long) {
    if (itemId == BRIDGE_ITEM) {
      bridgeEnded = bridgeSession
      Log.i(TAG, "bridge session ended session=$bridgeSession")
      return
    }
    store.finishReading(itemId, System.currentTimeMillis())
    Log.i(TAG, "playback finished item=$itemId")
  }

  /**
   * R-S05, ADR 0011: with Settings' switch on, the next unread item in List order, segmented
   * here with no JS (AGENTS.md 11). Read at each handover, so a toggle applies at the next end.
   * Ids and counts only in the log (AGENTS.md 1); the title goes to the notification.
   */
  override fun nextItem(finishedId: Long, skip: Set<Long>): NextItem? {
    // REA-39: a note read for Obsidian never hands over to the List.
    if (finishedId == BRIDGE_ITEM || !Settings(this).continuousPlay) return null
    val began = SystemClock.elapsedRealtime()
    val found = ContinuousPlay.next(store, finishedId, skip)
    val ms = SystemClock.elapsedRealtime() - began
    if (found == null) {
      Log.i(TAG, "playback continue none after=$finishedId ms=$ms")
      return null
    }
    offered = found.item.id to found.item.title
    val n = found.next
    Log.i(TAG, "playback continue from=$finishedId item=${n.itemId} sentences=${n.sentences.size} start=${n.startIndex} ms=$ms")
    return n
  }

  /**
   * REA-49: the item [nextItem] would offer if [currentId] ended now, for preparing its first
   * audio. On the queue's preparation thread. No title is offered (the handover's [nextItem] does
   * that). Ids, counts and ms only in the log (AGENTS.md 1).
   */
  override fun peekNext(currentId: Long, skip: Set<Long>): NextItem? {
    if (currentId == BRIDGE_ITEM || !Settings(this).continuousPlay) return null
    val began = SystemClock.elapsedRealtime()
    val found = ContinuousPlay.next(store, currentId, skip)
    val ms = SystemClock.elapsedRealtime() - began
    if (found == null) {
      Log.i(TAG, "playback prepare none after=$currentId ms=$ms")
      return null
    }
    val n = found.next
    Log.i(TAG, "playback prepare from=$currentId item=${n.itemId} sentences=${n.sentences.size} start=${n.startIndex} ms=$ms")
    return n
  }

  override fun handedOver(fromId: Long, toId: Long, ms: Long, prepared: Boolean) {
    Log.i(TAG, "playback handover from=$fromId item=$toId ms=$ms prepared=${if (prepared) 1 else 0}")
  }

  override fun engineLost() {
    // Called under the queue lock: a user Pause or Stop counted after this one did not exist
    // when the loss was found, so the rebind must not resume past it (REA-18, REA-51).
    val mark = userStops.get()
    main.post { if (!destroyed) recoverEngine(mark) }
  }

  override fun stall(detail: String) {
    Log.i(TAG, "playback stall $detail")
  }

  override fun firstAudio(itemId: Long, ms: Long) {
    Log.i(TAG, "playback first-audio ms=$ms item=$itemId")
  }

  override fun changed(snapshot: PlaybackSnapshot) {
    if (snapshot.itemId == BRIDGE_ITEM) snapshot.sentence?.let { bridgeIndex = it.paragraphIndex }
    PlaybackHub.publish(withWaiting(snapshot))
    main.post { if (!destroyed) onSnapshot(snapshot) }
  }

  // --- main thread ---

  private fun onSnapshot(s: PlaybackSnapshot) {
    val before = shown
    shown = s
    if (s.itemId != null && s.itemId != before?.itemId) {
      offered?.takeIf { it.first == s.itemId }?.let { title = it.second }
    }
    if (s.itemId == null) {
      logStats()
      // REA-35 #1: an item ending (or none yet) must not drop a request waiting for the engine.
      if (startingItemId() == null) end()
      return
    }
    if (s.playing && before?.playing != true) {
      if (before?.itemId == s.itemId) Log.i(TAG, "playback resumed item=${s.itemId}")
      if (!startPlaying()) return
    } else if (s.playing && before?.itemId != null && before.itemId != s.itemId) {
      // R-S05: a continuous-play handover keeps focus, the session and the foreground; the
      // wake lock's timeout restarts so a long chain is not cut at WAKE_LOCK_MS.
      wakeLock?.let { it.acquire(WAKE_LOCK_MS); wakeAcquiredAt = SystemClock.elapsedRealtime() }
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

  /**
   * REA-33: a new speaker for Settings' engine and voice; onReady resumes where it stopped.
   * Main thread only. REA-18: also a user Play on a speaker whose engine failed. [mark] is the
   * userStops count when that Play was given: a Pause or Stop after it means no resume.
   */
  private fun follow(mark: Int) {
    val failed = speakerFailed()
    speakerFor = choice()
    val old = speaker
    speaker = TtsSpeaker(this, this, speakerFor.second)
    queue.swapSynth(speaker) { old.shutdown() }
    rebinding = true
    rebindMark = mark
    resumeAfterRebind = userStops.get() == mark
    Log.i(TAG, if (failed) "playback engine rebinding for play" else "playback voice changed")
  }

  /**
   * REA-18: rebind playback's own engine (never the bridge's). It resumes where it stopped
   * unless the user paused or stopped after the loss was found ([mark], the userStops count
   * then, has moved).
   */
  private fun recoverEngine(mark: Int) {
    if (!RecoveryPolicy.rebind(rebinds, rebinding)) {
      if (!rebinding) Log.i(TAG, "playback engine lost; paused")
      return
    }
    rebinds++
    rebinding = true
    rebindMark = mark
    resumeAfterRebind = userStops.get() == mark
    Log.i(TAG, "playback engine lost; rebinding")
    val old = speaker
    speakerFor = choice()
    speaker = TtsSpeaker(this, this, speakerFor.second)
    queue.swapSynth(speaker) { old.shutdown() }
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
    main.removeCallbacks(stallTick)
    main.postDelayed(stallTick, STALL_TICK_MS)
    if (wakeLock == null) {
      // SPIKE-05 measured gaps on USB power without a wake lock; battery and Doze were never
      // measured, so playback holds one while playing: the engine synthesizes and the player
      // writes with the screen off (device:gap measures it).
      wakeLock = getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReadMe:playback")
        .apply { setReferenceCounted(false); acquire(WAKE_LOCK_MS) }
      wakeAcquiredAt = SystemClock.elapsedRealtime()
    }
    if (!noisyRegistered) {
      val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
      if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, filter, RECEIVER_NOT_EXPORTED)
      else registerReceiver(noisy, filter)
      noisyRegistered = true
    }
    return true
  }

  /** REA-18: a read longer than WAKE_LOCK_MS keeps its wake lock (WakeRenewal). */
  private fun renewWakeLock() {
    val wl = wakeLock ?: return
    val now = SystemClock.elapsedRealtime()
    if (!queue.snapshot().playing || !WakeRenewal.due(wakeAcquiredAt, now)) return
    wl.acquire(WAKE_LOCK_MS)
    wakeAcquiredAt = now
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
    val foreground = ServiceLife.foreground(s.playing, pausedForFocus, bridgeOn, held)
    // REA-18: one snapshot per sentence rebuilt and posted the notification each time (a rate
    // limit risk at 4x). Only a change in what it shows posts it.
    if (shownOf(s, foreground) == posted) return
    if (foreground) {
      goForeground(s)
    } else {
      // Paused for longer than ADR 0009's window: the notification stays (with Play) but can be
      // swiped away, and the system may stop the service; the position is already saved.
      stopForeground(STOP_FOREGROUND_DETACH)
      getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(s))
      posted = shownOf(s, foreground = false)
    }
  }

  /** Every input of [notification], plus whether it is the foreground one. */
  private fun shownOf(s: PlaybackSnapshot, foreground: Boolean): ServiceText.Shown {
    val bridgeOn = bridge != null
    return ServiceText.Shown(ServiceText.of(title, s, bridgeOn), s.playing, bridgeOn, foreground)
  }

  /** Always posts: onStartCommand owes Android a startForeground whatever is shown. */
  private fun goForeground(s: PlaybackSnapshot) {
    val n = notification(s)
    try {
      if (Build.VERSION.SDK_INT >= 29) {
        startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
      } else {
        startForeground(NOTIFICATION_ID, n)
      }
      posted = shownOf(s, foreground = true)
    } catch (e: IllegalStateException) {
      posted = null
      // Android 12+ refuses a foreground start from the background outside the exemptions
      // (notification action, media button). Stay paused rather than speak unprotected.
      Log.i(TAG, "playback resume refused: ${e.javaClass.simpleName}")
      queue.pause()
    }
  }

  private fun end() {
    main.removeCallbacks(pauseExpired)
    pausedAt = -1L
    if (waiting != null) {
      waiting = null
      publishState()
    }
    releasePlayingResources(abandon = true)
    session.isActive = false
    // R-M12: an enabled bridge keeps the service, and its notification, after playback ends.
    if (bridge != null) {
      goForeground(queue.snapshot())
      return
    }
    stopForeground(STOP_FOREGROUND_REMOVE)
    posted = null
    // REA-35 #2: stopSelf() would also discard a start Android delivered after this one.
    stopSelfResult(lastStartId)
  }

  private fun logStats() {
    val st = queue.takeStats()
    val g = st.gaps
    if (g.count == 0 && st.errors == 0 && st.saveErrors == 0) return
    // device-gap.sh reads these fields by name; REA-31's are appended after saveErrors.
    Log.i(TAG, "playback gaps n=${g.count} p50=${g.p50} p95=${g.p95} max=${g.max} stalls=${g.stalls} errors=${st.errors} saveErrors=${st.saveErrors} underruns=${st.underruns} synthP50=${st.synthP50} synthP95=${st.synthP95}")
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
    internal const val CHANNEL = "playback"
    const val NOTIFICATION_ID = 3001
    private const val WAKE_LOCK_MS = 4 * 60 * 60 * 1000L
    private const val STALL_TICK_MS = 5_000L

    /** REA-39: the item id of a bridge session. Store ids start at 1, so it is never an item. */
    const val BRIDGE_ITEM = -1L
    /** What the notification and lock screen show for it: never the note's own text or name. */
    const val BRIDGE_TITLE = "Obsidian note"

    /** REA-60: whether the service's notification can show; replaceable in tests only. */
    @VisibleForTesting @Volatile var notificationsShown: (Context) -> Boolean = NotificationGate::shown

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
      val enabled = Settings(context).bridgeEnabled
      // REA-60: every caller (MainActivity.onResume, setVoice, setEngine, setBridgeEnabled,
      // setBridgePort) comes through here, so none starts a bridge whose notification is hidden.
      val on = BridgeView.allowed(enabled, notificationsShown(context))
      if (enabled && !on) Log.i(TAG, "bridge blocked: notifications off")
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
