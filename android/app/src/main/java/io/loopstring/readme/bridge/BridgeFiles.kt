package io.loopstring.readme.bridge

import java.io.File

/** R-M12: synthesized WAVs live in the app cache; the server deletes each one, and start-up sweeps leftovers. */
class BridgeFiles(private val dir: File) {
  fun create(): File = File.createTempFile(PREFIX, ".wav", dir)

  fun sweep(): Int =
    dir.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(".wav") }?.count { it.delete() } ?: 0

  companion object {
    const val PREFIX = "bridge-"
  }
}
