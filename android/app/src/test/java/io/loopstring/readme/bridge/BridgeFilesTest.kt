package io.loopstring.readme.bridge

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeFilesTest {
  @Test fun createsWavsInTheDirAndSweepsOnlyThem() {
    val dir = Files.createTempDirectory("cache").toFile()
    val files = BridgeFiles(dir)
    val a = files.create()
    val b = files.create()
    val other = File(dir, "keep.wav").apply { writeText("x") }
    val notWav = File(dir, "bridge-notes.txt").apply { writeText("x") }
    assertTrue(a.name.startsWith("bridge-") && a.name.endsWith(".wav") && a.parentFile == dir)
    assertEquals(2, files.sweep())
    assertTrue(!a.exists() && !b.exists() && other.exists() && notWav.exists())
  }
}
