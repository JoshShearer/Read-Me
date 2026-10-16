package io.loopstring.readme

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.facebook.react.bridge.BridgeReactContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

// REA-56: device:continuous's Reader checks read this line instead of a uiautomator dump. It
// carries an id and a state only (AGENTS.md 1), in exactly the shape the script's ERE expects.
@RunWith(RobolectricTestRunner::class)
class ReaderShowsLogTest {
  @Before fun clear() = ShadowLog.clear()

  private fun lines(): List<ShadowLog.LogItem> = ShadowLog.getLogsForTag("ReadMe")

  @Test fun theReaderLineNamesTheItemAndWhetherItPlays() {
    val module = ReadMeSpeechModule(BridgeReactContext(ApplicationProvider.getApplicationContext<Context>()))
    module.readerShows(42.0, true)
    module.readerShows(7.0, false)
    val l = lines()
    assertEquals(listOf("reader shows item=42 playing=1", "reader shows item=7 playing=0"), l.map { it.msg })
    assertTrue(l.all { it.type == Log.INFO })
    // Nothing else rides on the line: an id and 0 or 1, no text of any kind.
    assertTrue(l.all { Regex("^reader shows item=-?[0-9]+ playing=[01]$").matches(it.msg) })
    assertTrue(l.all { it.throwable == null })
  }
}
