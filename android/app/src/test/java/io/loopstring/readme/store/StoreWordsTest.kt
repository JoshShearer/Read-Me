package io.loopstring.readme.store

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** REA-50, ADR 0013: words are runs outside JS `\s`, stored per paragraph and backfilled. */
@RunWith(RobolectricTestRunner::class)
class StoreWordsTest {
  private val ctx = ApplicationProvider.getApplicationContext<Context>()

  @After fun tearDown() = Store.resetForTest()

  private fun columns(db: SQLiteDatabase, table: String): List<String> =
    db.rawQuery("PRAGMA table_info($table)", null).use {
      val out = ArrayList<String>()
      while (it.moveToNext()) out.add(it.getString(1))
      out
    }

  private fun uncounted(store: Store): Int =
    store.readableDatabase.rawQuery("SELECT COUNT(*) FROM paragraphs WHERE words IS NULL", null).use {
      it.moveToFirst()
      it.getInt(0)
    }

  @Test fun countWordsMatchesJsWhitespace() {
    // JS `\s` (ECMA-262 WhiteSpace and LineTerminator), written out here so the count is tied
    // to JS and not only to Segmenter.isSpace.
    val js = (setOf(0x9, 0xA, 0xB, 0xC, 0xD, 0x20, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF) +
      (0x2000..0x200A)).toSet()
    for (code in 0..0xFFFF) {
      val c = code.toChar()
      if (Character.isSurrogate(c)) continue
      val expected = if (code in js) 2 else 1
      assertEquals("U+%04X".format(code), expected, Store.countWords("a${c}b"))
    }
    assertEquals(0, Store.countWords(""))
    assertEquals(0, Store.countWords(" \t\n "))
    assertEquals(2, Store.countWords("  a  b  "))
    assertEquals(1, Store.countWords("a\u0001b"))
    assertEquals(1, Store.countWords("😀"))
  }

  @Test fun aV1StoreIsCountedByTheBackfill() {
    val file = ctx.getDatabasePath("readme.db")
    file.parentFile!!.mkdirs()
    SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
      // The exact version-1 schema released in 1.0.0 to 1.0.5.
      db.execSQL(
        """CREATE TABLE items (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          kind TEXT NOT NULL CHECK (kind IN ('link', 'text')),
          url TEXT,
          title TEXT NOT NULL,
          site TEXT,
          byline TEXT,
          created_at INTEGER NOT NULL,
          state TEXT NOT NULL CHECK (state IN
            ('fetching', 'fetched', 'fetch-failed', 'extract-poor', 'ready')),
          fail_reason TEXT,
          opened_at INTEGER,
          archived_at INTEGER)""",
      )
      db.execSQL(
        """CREATE TABLE paragraphs (
          item_id INTEGER NOT NULL REFERENCES items(id) ON DELETE CASCADE,
          idx INTEGER NOT NULL,
          kind TEXT NOT NULL CHECK (kind IN ('p', 'heading', 'li')),
          text TEXT NOT NULL,
          PRIMARY KEY (item_id, idx))""",
      )
      db.execSQL(
        """CREATE TABLE cuts (
          item_id INTEGER NOT NULL REFERENCES items(id) ON DELETE CASCADE,
          paragraph_index INTEGER NOT NULL,
          PRIMARY KEY (item_id, paragraph_index))""",
      )
      db.execSQL(
        """CREATE TABLE positions (
          item_id INTEGER PRIMARY KEY REFERENCES items(id) ON DELETE CASCADE,
          paragraph_index INTEGER NOT NULL,
          char_offset INTEGER NOT NULL,
          updated_at INTEGER NOT NULL)""",
      )
      db.execSQL("INSERT INTO items (id, kind, title, created_at, state) VALUES (7, 'text', 't', 1, 'ready')")
      db.execSQL("INSERT INTO paragraphs (item_id, idx, kind, text) VALUES (7, 0, 'p', 'one' || char(10) || 'two')")
      db.execSQL("INSERT INTO paragraphs (item_id, idx, kind, text) VALUES (7, 1, 'p', 'a' || char(9) || 'b c')")
      db.version = 1
    }

    val store = Store.get(ctx)
    val db = store.readableDatabase
    assertTrue(columns(db, "paragraphs").contains("words"))
    assertEquals(
      1,
      db.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE name = 'paragraphs_uncounted'", null)
        .use { it.moveToFirst(); it.getInt(0) },
    )
    // Opening does not count; until the backfill runs, an uncounted row adds nothing.
    assertEquals(0, store.stats()[7L]!!.words)
    assertEquals(ItemStats(0, 12, 0), store.stats()[7L])

    assertTrue(Store.backfillAtStart(ctx))
    assertEquals(5, store.stats()[7L]!!.words)
    assertEquals(0, uncounted(store))
    assertEquals(1, db.version)
  }

  @Test fun aRowWrittenByOldCodeIsCountedByTheNextBackfill() {
    val store = Store.get(ctx)
    val id = store.insertText("t", listOf("x y"), 1000)
    // A version-1 build installed over this one inserts without the column.
    store.writableDatabase.execSQL(
      "INSERT INTO paragraphs (item_id, idx, kind, text) VALUES (?, 1, 'p', 'p q' || char(10) || 'r')",
      arrayOf<Any>(id),
    )
    assertEquals(1, uncounted(store))
    assertEquals(2, store.stats()[id]!!.words)
    Store.resetForTest()
    assertTrue(Store.backfillAtStart(ctx))
    assertEquals(5, Store.get(ctx).stats()[id]!!.words)
  }

  @Test fun theBackfillPagesThroughMoreRowsThanOnePage() {
    val store = Store.get(ctx)
    val id = store.insertText("t", emptyList<String>(), 1000)
    val db = store.writableDatabase
    for (i in 0 until 150) {
      db.execSQL(
        "INSERT INTO paragraphs (item_id, idx, kind, text) VALUES (?, ?, 'p', 'w1 w2')",
        arrayOf<Any>(id, i),
      )
    }
    assertEquals(150, uncounted(store))
    var pages = 0
    assertEquals(150, store.backfillWords(page = 64, onPage = { pages++ }))
    assertEquals(3, pages)
    assertEquals(0, uncounted(store))
    assertEquals(300, store.stats()[id]!!.words)
    // Nothing left: a second run reads no page.
    assertEquals(0, store.backfillWords(page = 64, onPage = { pages++ }))
    assertEquals(3, pages)
  }

  @Test fun aFailingBackfillLeavesTheStoreUsableAndRetriesLater() {
    val store = Store.get(ctx)
    val id = store.insertText("t", listOf("x y"), 1000)
    store.writableDatabase.execSQL(
      "INSERT INTO paragraphs (item_id, idx, kind, text) VALUES (?, 1, 'p', 'p q')",
      arrayOf<Any>(id),
    )
    assertFalse(Store.backfillAtStart(ctx) { throw IllegalStateException("boom") })
    // The store still opens and answers; the uncounted row adds nothing until a later run.
    Store.resetForTest()
    val again = Store.get(ctx)
    assertEquals(1, again.items().size)
    assertEquals(2, again.stats()[id]!!.words)
    assertEquals(1, uncounted(again))
    assertTrue(Store.backfillAtStart(ctx))
    assertEquals(4, again.stats()[id]!!.words)
  }

  @Test fun deletingAnItemDeletesItsCountedParagraphs() {
    val store = Store.get(ctx)
    val id = store.insertText("t", listOf("a b"), 1000)
    store.delete(id)
    assertEquals(
      0,
      store.readableDatabase.rawQuery("SELECT COUNT(*) FROM paragraphs", null).use { it.moveToFirst(); it.getInt(0) },
    )
  }

  @Test fun statsQueryStaysShallow() {
    // A deeply nested expression overflows SQLite's parser stack ("parser stack overflow"),
    // and Robolectric's SQLite is not Android 7's, so a prepare here alone proves too little.
    var depth = 0
    var max = 0
    for (c in Store.STATS_SQL) {
      if (c == '(') max = maxOf(max, ++depth)
      if (c == ')') depth--
    }
    assertEquals(0, depth)
    assertTrue("paren depth $max", max <= 6)
  }
}
