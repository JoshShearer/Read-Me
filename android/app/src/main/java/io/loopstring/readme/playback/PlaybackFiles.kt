package io.loopstring.readme.playback

import java.io.File

/**
 * REA-31 (ADR 0012): where the engine writes each sentence before Read Me plays it. The
 * directory is app-private and excluded from backup (`noBackupFilesDir/playback`, AGENTS.md 3).
 * A name carries the item id, the queue's generation and the sentence's paragraph and offset,
 * never text (AGENTS.md 1 and 10). The player deletes a file once it is in the output; the
 * queue deletes what it discards; the service sweeps the directory at start and at destroy.
 */
class PlaybackFiles(private val dir: File) {
  fun file(itemId: Long, generation: Int, row: SentenceRow): File {
    // synthesizeToFile opens the file in this process: a missing directory reads as a refusal.
    if (!dir.isDirectory) dir.mkdirs()
    return File(dir, "$PREFIX$itemId-$generation-${row.paragraphIndex}-${row.start}.wav")
  }

  fun sweep(): Int =
    dir.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(".wav") }?.count { it.delete() } ?: 0

  companion object {
    const val PREFIX = "play-"
  }
}
