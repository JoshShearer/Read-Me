package io.loopstring.readme.playback

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFilesTest {
  private val dir = File(Files.createTempDirectory("pb").toFile(), "playback")
  private val files = PlaybackFiles(dir)

  @Test fun aNameCarriesIdsAndOffsetsNeverText() {
    // AGENTS.md 1 and 10: the file name is the item, generation, paragraph and offset.
    val f = files.file(7, 3, SentenceRow(2, 41, 60, "Quietly the heron waited."))
    assertEquals("play-7-3-2-41.wav", f.name)
    assertEquals(dir, f.parentFile)
    assertFalse(f.name.contains("heron"))
  }

  @Test fun theDirectoryIsMadeWhenMissing() {
    // synthesizeToFile opens the file in this process; a missing directory reads as a refusal.
    assertFalse(dir.exists())
    files.file(1, 0, SentenceRow(0, 0, 3, "A."))
    assertTrue(dir.isDirectory)
  }

  @Test fun sweepDeletesOnlyPlaybackFiles() {
    dir.mkdirs()
    val a = File(dir, "play-1-0-0-0.wav").apply { writeText("x") }
    val b = File(dir, "play-1-0-0-5.wav").apply { writeText("x") }
    val other = File(dir, "keep.txt").apply { writeText("x") }
    assertEquals(2, files.sweep())
    assertFalse(a.exists())
    assertFalse(b.exists())
    assertTrue(other.exists())
    assertEquals(0, PlaybackFiles(File(dir, "absent")).sweep())
  }

  @Test fun deleteItemTakesOnlyThatItemsFiles() {
    // Item 1 must not take item 12's files, nor a non-wav of its own.
    dir.mkdirs()
    val a = File(dir, "play-1-0-0-0.wav").apply { writeText("x") }
    val b = File(dir, "play-1-3-2-5.wav").apply { writeText("x") }
    val twelve = File(dir, "play-12-0-0-0.wav").apply { writeText("x") }
    val note = File(dir, "play-1-0-0-0.txt").apply { writeText("x") }
    assertEquals(2, files.deleteItem(1))
    assertFalse(a.exists())
    assertFalse(b.exists())
    assertTrue(twelve.exists())
    assertTrue(note.exists())
    assertEquals(0, PlaybackFiles(File(dir, "absent")).deleteItem(1))
  }

  @Test fun preparedNamesCarryIdsNeverTextAndAreSweptAndDeletedWithTheirItem() {
    // REA-49: audio prepared for the next item before a handover; AGENTS.md 1, 3 and 10.
    val f = files.prepared(1, 4, SentenceRow(2, 41, 60, "Quietly the heron waited."))
    assertEquals("play-1-p4-2-41.wav", f.name)
    assertFalse(f.name.contains("heron"))
    f.writeText("wav")
    val other = files.prepared(12, 4, SentenceRow(0, 0, 3, "A.")).apply { writeText("wav") }
    assertEquals(1, files.deleteItem(1))
    assertFalse(f.exists())
    assertTrue("item 1 must not match item 12's files", other.exists())
    assertEquals(1, files.sweep())
    assertFalse(other.exists())
  }
}
