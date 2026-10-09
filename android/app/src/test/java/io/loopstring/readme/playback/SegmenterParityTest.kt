package io.loopstring.readme.playback

import io.loopstring.readme.store.PositionRow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * ADR 0011: the Kotlin segmenter must give the TS fallback's exact offsets, remap and resume
 * index on every case of the golden that __tests__/segmentParity.test.ts pins to the TS code.
 * Robolectric only for org.json.
 */
@RunWith(RobolectricTestRunner::class)
class SegmenterParityTest {
  private fun golden(): JSONObject {
    // Gradle runs unit tests in android/app; walk up to the repo root.
    var dir: File? = File("").absoluteFile
    while (dir != null && !File(dir, "__tests__/fixtures/segment-parity.json").exists()) dir = dir.parentFile
    requireNotNull(dir) { "segment-parity.json not found" }
    return JSONObject(File(dir, "__tests__/fixtures/segment-parity.json").readText())
  }

  private fun ints(a: JSONArray) = (0 until a.length()).map { a.getInt(it) }

  @Test fun theKotlinSegmenterMatchesTheTsGoldenOnEveryCase() {
    val cases = golden().getJSONArray("cases")
    assertTrue(cases.length() >= 5)
    var checked = 0
    for (c in 0 until cases.length()) {
      val case = cases.getJSONObject(c)
      val name = case.getString("name")
      val pa = case.getJSONArray("paragraphs")
      val paragraphs = (0 until pa.length()).map { pa.getString(it) }
      val sa = case.getJSONArray("saved")
      val saved = (0 until sa.length()).map { if (sa.isNull(it)) null else ints(sa.getJSONArray(it)).let { p -> PositionRow(p[0], p[1]) } }
      val variants = case.getJSONArray("variants")
      for (v in 0 until variants.length()) {
        val variant = variants.getJSONObject(v)
        val cuts = ints(variant.getJSONArray("cuts")).toSet()
        val want = variant.getJSONArray("sentences").let { a -> (0 until a.length()).map { ints(a.getJSONArray(it)) } }
        val got = Segmenter.segment(paragraphs, cuts)
        assertEquals("$name cuts#$v", want, got.map { listOf(it.paragraphIndex, it.start, it.end) })
        got.forEach { assertEquals(paragraphs[it.paragraphIndex].substring(it.start, it.end), it.text) }
        val plans = variant.getJSONArray("plans")
        saved.forEachIndexed { i, pos ->
          val p = Segmenter.plan(paragraphs, cuts, pos)
          val expected = if (plans.isNull(i)) null else plans.getJSONArray(i).let { listOf(it.getInt(0), it.getBoolean(1)) }
          assertEquals("$name cuts#$v saved#$i", expected, p?.let { listOf(it.startIndex, it.pastEnd) })
        }
        checked += got.size
      }
    }
    assertTrue(checked > 1000)
  }
}
