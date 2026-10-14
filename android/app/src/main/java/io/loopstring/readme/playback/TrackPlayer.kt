package io.loopstring.readme.playback

import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/** Read Me's own audio output for synthesized sentences (REA-31, ADR 0012). */
interface Player {
  /** Plays [file] after everything enqueued before it; the player owns and deletes it. */
  fun enqueue(id: String, file: File)
  fun play()
  /** A true pause: the output holds its place mid-sentence (D1). */
  fun pause()
  /** Discards everything enqueued or buffered and deletes the files. */
  fun flush()
  /** Sentence frames heard so far; silence does not count. Grows only. */
  fun progress(): Long
  fun takeUnderruns(): Int
}

/** Called on the player's thread with no player lock held. */
interface PlayerEvents {
  /** The sentence's first frame has been played: R-M07's "started", and R-M11's position. */
  fun onPlayerStart(id: String)
  /** The sentence's last frame has been played. */
  fun onPlayerDone(id: String)
  /** The file could not be read or played; nothing of it was heard. */
  fun onPlayerError(id: String)
}

/** One streaming output, an AudioTrack on the phone. Written from the player's thread only. */
interface AudioOut {
  val format: PcmFormat
  /** Frames the output wants buffered before it starts playing at all. */
  val startFrames: Int
  /** Non-blocking: the bytes accepted (0 when full), negative on an error. */
  fun write(buf: ByteArray, off: Int, len: Int): Int
  fun play()
  fun pause()
  fun release()
  /** Frames played since this output was opened. */
  fun head(): Long
  fun underruns(): Int
}

/**
 * REA-31 (ADR 0012): plays the engine's files on one streaming output at 1.0x. The engine
 * applied the rate when it synthesized; nothing here scales speed (AGENTS.md 9). Audio played
 * here is Read Me's, so Android sends media keys to Read Me's session whatever the engine.
 *
 * Sentences are written back to back; their boundaries are frame counts, and the play head
 * decides when a sentence starts and ends, so a listener's gap is what R-M07 measures (D3).
 * When the next sentence is not ready the output is fed silence instead of running dry: an
 * output that runs dry holds the end of the last sentence back, so the gap would read as zero.
 *
 * [step] does one round of work; [start] runs it on a thread. Tests call [step] directly.
 *
 * REA-54: the thread polls every [IDLE_MS] only while an output exists (its head, marks and
 * silence need watching, paused or not). With no output and nothing to read it waits with no
 * timeout; every call that can give it work bumps [changes] and wakes it, and the loop sleeps
 * only if [changes] is what [step] saw, checked under the lock it waits on, so no wakeup is lost.
 */
class TrackPlayer(
  private val events: PlayerEvents,
  private val open: (PcmFormat) -> AudioOut?,
  private val padMs: Int = PAD_MS,
  private val log: (String) -> Unit = {},
) : Player {
  private class Entry(val id: String, val file: File)

  private class Mark(val id: String, val start: Long) {
    var end = -1L
    var started = false
    var counted = 0L
  }

  private class Reading(val id: String, val file: File, val input: InputStream, val format: PcmFormat, var left: Long) {
    fun close() {
      runCatching { input.close() }
      file.delete()
    }
  }

  private val lock = Object()
  // Guarded by lock.
  private val pending = ArrayDeque<Entry>()
  private var want = false
  private var epoch = 0
  private var closed = false
  private var taken = 0
  private var thread: Thread? = null
  /** Bumped with every notifyAll: a round that saw an older value has not seen that work. */
  private var changes = 0L

  // The writer's own state: only step() and reset() touch it.
  private var seen = 0
  private var seenChanges = 0L
  @Volatile private var out: AudioOut? = null
  private var outPlaying = false
  private var writtenBytes = 0L
  private var started = false
  private var reading: Reading? = null
  private var waiting: Reading? = null // a sentence in another format, after the current output drains
  private val marks = ArrayDeque<Mark>()
  private val buf = ByteArray(CHUNK)
  private var carryOff = 0
  private var carryLen = 0
  @Volatile private var retired = 0
  @Volatile private var played = 0L

  override fun enqueue(id: String, file: File) = synchronized(lock) {
    if (closed) {
      file.delete()
      return
    }
    pending.addLast(Entry(id, file))
    wakeLocked()
  }

  override fun play() = synchronized(lock) {
    want = true
    wakeLocked()
  }

  override fun pause() = synchronized(lock) {
    want = false
    wakeLocked()
  }

  override fun flush() = synchronized(lock) {
    epoch++
    want = false
    pending.forEach { it.file.delete() }
    pending.clear()
    wakeLocked()
  }

  override fun progress(): Long = played

  override fun takeUnderruns(): Int = synchronized(lock) {
    val total = retired + (out?.underruns() ?: 0)
    (total - taken).coerceAtLeast(0).also { taken = total }
  }

  fun start() = synchronized(lock) {
    if (thread != null || closed) return
    thread = Thread(::loop, "ReadMe-audio").apply { isDaemon = true; start() }
  }

  /** Stops the thread, releases the output and deletes every file the player still holds. */
  fun release() {
    val t = synchronized(lock) {
      closed = true
      epoch++
      pending.forEach { it.file.delete() }
      pending.clear()
      wakeLocked()
      thread
    }
    if (t == null) reset() else t.join(JOIN_MS)
  }

  /** Under [lock]: work may have arrived. */
  private fun wakeLocked() {
    changes++
    lock.notifyAll()
  }

  private fun loop() {
    runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
    while (true) {
      if (synchronized(lock) { closed }) break
      val wait = try {
        step()
      } catch (e: Throwable) {
        // An Error too (as handOver, REA-46): a dead writer would read later as a lost engine.
        // The class only (AGENTS.md 1). Whatever was buffered is lost: say so for each sentence,
        // so the queue skips it instead of waiting for a "done" that never comes.
        log("playback output failed: ${e.javaClass.simpleName}")
        failAll()
        IDLE_MS
      }
      synchronized(lock) {
        if (closed) {
          // release() woke it; the while check ends the loop.
        } else if (wait == UNTIL_WOKEN) {
          // Decided by step() outside the lock: sleep only if nothing changed since it looked.
          // pending and epoch are checked too, so a sleep never leaves queued work behind.
          if (changes == seenChanges && epoch == seen && pending.isEmpty()) lock.wait()
        } else if (wait > 0) {
          lock.wait(wait)
        }
      }
    }
    reset()
  }

  /**
   * One round: follow a flush, play or pause, report what the head passed, then write. Returns
   * how long to wait before the next round: [UNTIL_WOKEN] when there is nothing to watch.
   */
  fun step(): Long {
    val (e, w) = synchronized(lock) {
      seenChanges = changes
      epoch to want
    }
    if (e != seen) {
      reset()
      seen = e
    }
    out?.let { o ->
      if (w != outPlaying) {
        if (w) o.play() else o.pause()
        outPlaying = w
      }
      if (!emit(o)) return 0
    }
    write(w)
    return if (idle()) UNTIL_WOKEN else IDLE_MS
  }

  /**
   * Nothing for the writer until a call gives it work: no output to watch (a head that moves,
   * marks to report, silence to pad or a drain before a format change all need one) and nothing
   * half read. pending is checked again under the lock before the wait.
   */
  private fun idle() = out == null && reading == null && waiting == null && carryLen == 0

  /** False when an event flushed the player: the rest of this round belongs to old work. */
  private fun emit(o: AudioOut): Boolean {
    val head = o.head()
    if (head > 0) started = true
    val written = writtenBytes / o.format.frameBytes
    while (marks.isNotEmpty()) {
      val m = marks.first()
      val len = (if (m.end >= 0) m.end else written) - m.start
      val heard = (head - m.start).coerceIn(0L, len)
      if (heard > m.counted) {
        played += heard - m.counted
        m.counted = heard
      }
      val over = m.end >= 0 && head >= m.end
      if (!m.started && (head > m.start || over)) {
        m.started = true
        events.onPlayerStart(m.id)
        if (flushed()) return false
      }
      if (!over) break
      marks.removeFirst()
      events.onPlayerDone(m.id)
      if (flushed()) return false
    }
    return true
  }

  private fun flushed() = synchronized(lock) { epoch != seen }

  private fun write(w: Boolean) {
    while (true) {
      val o = out
      if (carryLen > 0) {
        if (o == null) { carryLen = 0; continue }
        val n = o.write(buf, carryOff, carryLen)
        if (n < 0) throw IOException("write $n")
        writtenBytes += n
        carryOff += n
        carryLen -= n
        if (carryLen > 0) return // the output is full
        continue
      }
      val r = reading
      if (r != null) {
        if (r.left == 0L) { finish(r); continue }
        val fb = r.format.frameBytes
        val wantBytes = (if (r.left < 0) CHUNK.toLong() else minOf(CHUNK.toLong(), r.left)).toInt() / fb * fb
        val n = readUpTo(r.input, if (wantBytes > 0) wantBytes else fb)
        // A trailing partial frame is dropped: an output takes whole frames only.
        val whole = n / fb * fb
        if (whole <= 0) { finish(r); continue }
        if (r.left > 0) r.left = (r.left - n).coerceAtLeast(0)
        carryOff = 0
        carryLen = whole
        continue
      }
      if (!next()) break
    }
    pad(w)
  }

  private fun finish(r: Reading) {
    r.close()
    reading = null
    val o = out ?: return
    marks.lastOrNull()?.let { if (it.id == r.id && it.end < 0) it.end = writtenBytes / o.format.frameBytes }
  }

  /** Opens the next file into [reading]; false when there is nothing to write now. */
  private fun next(): Boolean {
    val r = waiting ?: run {
      val entry = synchronized(lock) { if (epoch != seen) null else pending.removeFirstOrNull() } ?: return false
      openFile(entry) ?: return true
    }
    val o = out
    if (o != null && o.format != r.format) {
      // The engine changed (a rebind): let this output finish its last sentence first.
      if (marks.isNotEmpty()) {
        waiting = r
        return false
      }
      retire(o)
    }
    waiting = null
    if (out == null) {
      val made = open(r.format)
      if (made == null) {
        log("playback output failed: open")
        r.close()
        events.onPlayerError(r.id)
        return !flushed()
      }
      out = made
      writtenBytes = 0
      started = false
      outPlaying = false
      if (synchronized(lock) { want }) {
        made.play()
        outPlaying = true
      }
    }
    reading = r
    marks.addLast(Mark(r.id, writtenBytes / r.format.frameBytes))
    return true
  }

  private fun openFile(e: Entry): Reading? {
    val input = try {
      BufferedInputStream(FileInputStream(e.file), CHUNK)
    } catch (x: IOException) {
      null
    }
    val header = input?.let { WavPcm.readHeader(it) }
    if (input == null || header == null) {
      runCatching { input?.close() }
      e.file.delete()
      events.onPlayerError(e.id)
      return null
    }
    return Reading(e.id, e.file, input, header.format, header.dataBytes)
  }

  /** Silence while starved, so the last sentence plays out and the gap is heard as it is. */
  private fun pad(w: Boolean) {
    val o = out ?: return
    if (!w || carryLen > 0 || reading != null) return
    val fb = o.format.frameBytes
    val frames = o.format.sampleRate.toLong() * padMs / 1000
    val target = if (started) frames else maxOf(frames, o.startFrames.toLong())
    var buffered = writtenBytes / fb - o.head()
    val silence: Byte = if (o.format.encoding == PcmFormat.Encoding.PCM_8) 0x80.toByte() else 0
    while (buffered < target) {
      val bytes = (minOf(target - buffered, (CHUNK / fb).toLong()) * fb).toInt()
      java.util.Arrays.fill(buf, 0, bytes, silence)
      val n = o.write(buf, 0, bytes)
      if (n < 0) throw IOException("write $n")
      if (n == 0) return
      writtenBytes += n
      buffered = writtenBytes / fb - o.head()
    }
  }

  private fun readUpTo(input: InputStream, want: Int): Int {
    var off = 0
    while (off < want) {
      val n = input.read(buf, off, want - off)
      if (n < 0) break
      off += n
    }
    return off
  }

  private fun retire(o: AudioOut) {
    // Under the lock takeUnderruns holds (main thread): it must never reach a released track,
    // whose getUnderrunCount throws.
    synchronized(lock) {
      retired += runCatching { o.underruns() }.getOrDefault(0)
      out = null
    }
    runCatching { o.release() }
    outPlaying = false
    writtenBytes = 0
    started = false
  }

  private fun reset() {
    reading?.close()
    reading = null
    waiting?.close()
    waiting = null
    out?.let { retire(it) }
    marks.clear()
    carryLen = 0
  }

  private fun failAll() {
    val ids = marks.map { it.id } + listOfNotNull(reading?.id, waiting?.id).filter { id -> marks.none { it.id == id } }
    runCatching { reset() }
    ids.forEach { events.onPlayerError(it) }
  }

  companion object {
    /** Silence kept buffered while starved: above one mixer period, small enough to be quick. */
    const val PAD_MS = 60
    const val IDLE_MS = 10L
    /** [step]'s answer when only a call can make work: wait with no timeout. */
    const val UNTIL_WOKEN = -1L
    private const val CHUNK = 16_384
    private const val JOIN_MS = 1_000L

    /** An AudioTrack for [format]: USAGE_MEDIA, CONTENT_TYPE_SPEECH, streaming, at 1.0x. */
    fun track(format: PcmFormat): AudioOut? = runCatching { TrackOut.open(format) }.getOrNull()
  }
}

private class TrackOut(override val format: PcmFormat, private val track: AudioTrack) : AudioOut {
  override val startFrames: Int =
    if (Build.VERSION.SDK_INT >= 31) track.startThresholdInFrames else track.bufferSizeInFrames

  override fun write(buf: ByteArray, off: Int, len: Int): Int =
    track.write(ByteBuffer.wrap(buf, off, len), len, AudioTrack.WRITE_NON_BLOCKING)

  override fun play() = track.play()
  override fun pause() = track.pause()

  override fun release() {
    runCatching { track.pause() }
    runCatching { track.flush() }
    track.release()
  }

  override fun head(): Long = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
  override fun underruns(): Int = track.underrunCount

  companion object {
    fun open(format: PcmFormat): AudioOut? {
      val enc = when (format.encoding) {
        PcmFormat.Encoding.PCM_8 -> AudioFormat.ENCODING_PCM_8BIT
        PcmFormat.Encoding.PCM_16 -> AudioFormat.ENCODING_PCM_16BIT
        PcmFormat.Encoding.FLOAT -> AudioFormat.ENCODING_PCM_FLOAT
      }
      val mask = if (format.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
      val min = AudioTrack.getMinBufferSize(format.sampleRate, mask, enc)
      // At least 200 ms: the writer wakes every 10 ms while an output exists, and a pause or
      // flush discards nothing heard.
      val size = maxOf(if (min > 0) min * 2 else 0, format.sampleRate * format.frameBytes / 5)
      val t = AudioTrack.Builder()
        .setAudioAttributes(TtsSpeaker.ATTRIBUTES)
        .setAudioFormat(AudioFormat.Builder().setEncoding(enc).setSampleRate(format.sampleRate).setChannelMask(mask).build())
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(size)
        .build()
      if (t.state != AudioTrack.STATE_INITIALIZED) {
        t.release()
        return null
      }
      return TrackOut(format, t)
    }
  }
}
