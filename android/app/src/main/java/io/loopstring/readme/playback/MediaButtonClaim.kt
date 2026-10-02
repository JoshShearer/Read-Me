package io.loopstring.readme.playback

import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler

/**
 * Android sends media keys (headset, `cmd media_session dispatch`) to the session of the app
 * that most recently played audio. TextToSpeech audio is played by the engine's process, so
 * without this Read Me never becomes that app and its session never gets a key (seen on the
 * reference device, 2026-10-02, build 3ba673c: audio attributed to the engine's uid, "Media
 * button session is null"). A fifth of a second of silence from this process puts Read Me's
 * uid in the playback history. It is played at every start and resume.
 */
class MediaButtonClaim(private val handler: Handler) {
  private var track: AudioTrack? = null

  fun claim() {
    release()
    val frames = SAMPLE_RATE / 5
    val t = runCatching {
      AudioTrack.Builder()
        .setAudioAttributes(TtsSpeaker.ATTRIBUTES)
        .setAudioFormat(
          AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build(),
        )
        .setTransferMode(AudioTrack.MODE_STATIC)
        .setBufferSizeInBytes(frames * 2)
        .build()
    }.getOrNull() ?: return
    runCatching {
      t.write(ShortArray(frames), 0, frames)
      t.play()
    }
    track = t
    handler.postDelayed({ if (track === t) release() }, 1_000)
  }

  fun release() {
    track?.let { runCatching { it.stop() }; it.release() }
    track = null
  }

  private companion object {
    const val SAMPLE_RATE = 8_000
  }
}
