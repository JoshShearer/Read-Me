package io.loopstring.readme.store

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/** REA-72, ADR 0017: the unread List is a stored queue order; new items go to the bottom. */
@RunWith(RobolectricTestRunner::class)
class StoreOrderTest {
  private val ctx = ApplicationProvider.getApplicationContext<Context>()

  @After fun tearDown() = Store.resetForTest()

  private fun store() = Store.get(ctx)

  private fun unread(s: Store) = s.items().filter { it.archivedAt == null }.map { it.id }

  private fun archived(s: Store) = s.items().filter { it.archivedAt != null }.map { it.id }

  private fun orders(s: Store): List<Long?> =
    s.readableDatabase.rawQuery("SELECT list_order FROM items ORDER BY id", null).use {
      val out = ArrayList<Long?>()
      while (it.moveToNext()) out.add(if (it.isNull(0)) null else it.getLong(0))
      out
    }

  private fun columns(db: SQLiteDatabase, table: String): List<String> =
    db.rawQuery("PRAGMA table_info($table)", null).use {
      val out = ArrayList<String>()
      while (it.moveToNext()) out.add(it.getString(1))
      out
    }

  private val listeners = ArrayList<() -> Unit>()

  @After fun dropListeners() = listeners.forEach { ItemEvents.remove(it) }

  private fun counting(): () -> Int {
    var n = 0
    val l: () -> Unit = { n++ }
    listeners.add(l)
    ItemEvents.add(l)
    return { n }
  }

  // --- New items go to the bottom ---

  @Test fun aLinkATextAndAFileLandAtTheBottomWhateverTheirTime() {
    val s = store()
    val a = s.findUnreadLinkOrInsert("https://a.com/x", "a.com", 3000).id
    val b = s.insertText("B", listOf("Bee."), 1000)
    val c = s.insertText("C", listOf(ParagraphRow("heading", "Sea"), ParagraphRow("p", "Cee.")), 2000)
    val d = s.findUnreadLinkOrInsert("https://d.com/x", "d.com", 500).id
    assertEquals(listOf(a, b, c, d), unread(s))
  }

  @Test fun equalTimesStayInInsertOrder() {
    val s = store()
    val a = s.insertText("A", listOf("A."), 1000)
    val b = s.insertText("B", listOf("B."), 1000)
    val c = s.insertLink("https://c.com", "c.com", 1000)
    assertEquals(listOf(a, b, c), unread(s))
  }

  @Test fun aMatchedLinkShareStaysWhereItIsAndWritesNothing() {
    val s = store()
    val first = s.findUnreadLinkOrInsert("https://a.com/x", "a.com", 1000).id
    val second = s.insertText("B", listOf("B."), 2000)
    assertTrue(s.move(second, Move.TOP))
    val before = orders(s)
    val events = counting()
    val again = s.findUnreadLinkOrInsert("https://a.com/x", "a.com", 3000)
    assertEquals(LinkShare(first, created = false), again)
    assertEquals(listOf(second, first), unread(s))
    assertEquals(before, orders(s))
    assertEquals(0, events())
  }

  @Test fun aRestoredItemGoesToTheBottomAndRestoringAnUnreadOneChangesNothing() {
    val s = store()
    val a = s.insertText("A", listOf("A."), 1000)
    val b = s.insertText("B", listOf("B."), 2000)
    val c = s.insertText("C", listOf("C."), 3000)
    s.archive(a, 10)
    assertEquals(listOf(b, c), unread(s))
    s.restore(a)
    assertEquals(listOf(b, c, a), unread(s))
    val before = orders(s)
    s.restore(b)
    assertEquals(before, orders(s))
    assertEquals(listOf(b, c, a), unread(s))
  }

  @Test fun theArchiveIsNewestSharedFirstWhateverTheMoves() {
    val s = store()
    val a = s.insertText("A", listOf("A."), 1000)
    val b = s.insertText("B", listOf("B."), 3000)
    val c = s.insertText("C", listOf("C."), 2000)
    val d = s.insertText("D", listOf("D."), 2000)
    assertTrue(s.move(a, Move.BOTTOM))
    for (id in listOf(a, b, c, d)) s.archive(id, 10)
    assertEquals(listOf(b, d, c, a), archived(s))
    // items(): unread rows first, in list order, then the archive.
    val e = s.insertText("E", listOf("E."), 0)
    assertEquals(listOf(e, b, d, c, a), s.items().map { it.id })
  }

  // --- Moves ---

  @Test fun everyMoveFromTheTopTheMiddleAndTheBottom() {
    val s = store()
    val (a, b, c, d) = (1..4).map { s.insertText("t$it", listOf("x."), it * 1000L) }
    assertTrue(s.move(d, Move.TOP))
    assertEquals(listOf(d, a, b, c), unread(s))
    assertTrue(s.move(d, Move.DOWN))
    assertEquals(listOf(a, d, b, c), unread(s))
    assertTrue(s.move(d, Move.DOWN))
    assertEquals(listOf(a, b, d, c), unread(s))
    assertTrue(s.move(d, Move.UP))
    assertEquals(listOf(a, d, b, c), unread(s))
    assertTrue(s.move(a, Move.BOTTOM))
    assertEquals(listOf(d, b, c, a), unread(s))
    assertTrue(s.move(c, Move.TOP))
    assertEquals(listOf(c, d, b, a), unread(s))
  }

  @Test fun aMoveAtItsEdgeOrOfAMissingOrArchivedItemDoesNothingAndFiresNothing() {
    val s = store()
    val a = s.insertText("A", listOf("A."), 1000)
    val b = s.insertText("B", listOf("B."), 2000)
    val c = s.insertText("C", listOf("C."), 3000)
    s.archive(c, 10)
    val before = orders(s)
    val events = counting()
    assertFalse(s.move(a, Move.TOP))
    assertFalse(s.move(a, Move.UP))
    assertFalse(s.move(b, Move.DOWN))
    assertFalse(s.move(b, Move.BOTTOM))
    assertFalse(s.move(c, Move.TOP))
    assertFalse(s.move(999, Move.TOP))
    assertEquals(before, orders(s))
    assertEquals(0, events())
    assertTrue(s.move(b, Move.UP))
    assertEquals(1, events())
  }

  @Test fun aMoveSkipsArchivedRowsAndKeepsEveryValueUnique() {
    val s = store()
    val ids = (1..6).map { s.insertText("t$it", listOf("x."), it * 1000L) }
    s.archive(ids[1], 10)
    s.archive(ids[3], 10)
    // Unread: 0, 2, 4, 5. Down from 0 passes the archived 1 and lands below 2.
    assertTrue(s.move(ids[0], Move.DOWN))
    assertEquals(listOf(ids[2], ids[0], ids[4], ids[5]), unread(s))
    assertTrue(s.move(ids[5], Move.UP))
    assertEquals(listOf(ids[2], ids[0], ids[5], ids[4]), unread(s))
    val values = orders(s)
    assertTrue(values.none { it == null })
    assertEquals(values.size, values.toSet().size)
  }

  @Test fun theOrderSurvivesANewProcess() {
    val s = store()
    val (a, b, c) = (1..3).map { s.insertText("t$it", listOf("x."), it * 1000L) }
    assertTrue(s.move(c, Move.TOP))
    assertTrue(s.move(a, Move.BOTTOM))
    Store.resetForTest()
    assertEquals(listOf(c, b, a), unread(store()))
  }

  @Test fun movesAndTheMigrationLeaveCutsAndPositionsAlone() {
    val s = store()
    val a = s.insertText("A", listOf("One.", "Two."), 1000)
    val b = s.insertText("B", listOf("B."), 2000)
    s.setCuts(a, listOf(1))
    s.savePosition(a, 0, 2, 5)
    assertTrue(s.move(a, Move.BOTTOM))
    Store.resetForTest()
    val again = store()
    assertEquals(listOf(b, a), unread(again))
    assertEquals(listOf(1), again.cuts(a))
    assertEquals(PositionRow(0, 2), again.position(a))
  }

  @Test fun moveNamesMapExplicitlyAndAnUnknownOneThrowsWithoutEchoingIt() {
    assertEquals(Move.TOP, Move.of("top"))
    assertEquals(Move.UP, Move.of("up"))
    assertEquals(Move.DOWN, Move.of("down"))
    assertEquals(Move.BOTTOM, Move.of("bottom"))
    try {
      Move.of("sideways-secret")
      fail("expected a throw")
    } catch (e: IllegalArgumentException) {
      assertFalse(e.message.orEmpty().contains("sideways-secret"))
    }
  }

  // --- The migration ---

  private fun v1Store(extra: (SQLiteDatabase) -> Unit = {}) {
    val file = ctx.getDatabasePath("readme.db")
    file.parentFile!!.mkdirs()
    SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
      V1Schema.create(db)
      extra(db)
      // created_at 300, 100, 200, then two at 150 (ids 4, 5), and an archived one.
      db.execSQL("INSERT INTO items (id, kind, title, created_at, state) VALUES (1, 'text', 'Secret title one', 300, 'ready')")
      db.execSQL("INSERT INTO items (id, kind, title, created_at, state) VALUES (2, 'text', 'Secret title two', 100, 'ready')")
      db.execSQL("INSERT INTO items (id, kind, title, created_at, state) VALUES (3, 'text', 'Secret title three', 200, 'ready')")
      db.execSQL("INSERT INTO items (id, kind, title, created_at, state) VALUES (5, 'text', 'Secret title five', 150, 'ready')")
      db.execSQL("INSERT INTO items (id, kind, title, created_at, state) VALUES (4, 'text', 'Secret title four', 150, 'ready')")
      db.execSQL("INSERT INTO items (id, kind, title, created_at, state, archived_at) VALUES (6, 'text', 'Secret title six', 250, 'ready', 9)")
      db.execSQL("INSERT INTO paragraphs (item_id, idx, kind, text) VALUES (1, 0, 'p', 'One.')")
      db.execSQL("INSERT INTO cuts (item_id, paragraph_index) VALUES (1, 0)")
      db.execSQL("INSERT INTO positions (item_id, paragraph_index, char_offset, updated_at) VALUES (3, 0, 4, 1)")
      db.version = 1
    }
  }

  private fun assertMigrated() {
    ShadowLog.clear()
    val s = store()
    val db = s.readableDatabase
    assertTrue(columns(db, "items").contains("list_order"))
    assertEquals(1, db.version)
    // Oldest first, equal times by id.
    assertEquals(listOf(2L, 4L, 5L, 3L, 1L), unread(s))
    assertEquals(listOf(6L), archived(s))
    assertTrue(orders(s).none { it == null })
    assertEquals(listOf(0), s.cuts(1))
    assertEquals(PositionRow(0, 4), s.position(3))
    val lines = ShadowLog.getLogsForTag("ReadMe").map { it.msg }
    val numbered = lines.filter { it.startsWith("store order numbered") }
    assertEquals(1, numbered.size)
    assertTrue(numbered[0], Regex("store order numbered rows=6 ms=\\d+").matches(numbered[0]))
    assertTrue(lines.none { it.contains("Secret") })
  }

  @Test fun aV1StoreIsNumberedOldestFirst() {
    v1Store()
    assertMigrated()
  }

  @Test fun a11xStoreWithWordsIsNumberedOldestFirst() {
    v1Store { db -> db.execSQL("ALTER TABLE paragraphs ADD COLUMN words INTEGER") }
    assertMigrated()
  }

  @Test fun aReopenDoesNotRenumber() {
    v1Store()
    val s = store()
    assertTrue(s.move(1, Move.TOP))
    val before = orders(s)
    Store.resetForTest()
    ShadowLog.clear()
    val again = store()
    assertEquals(before, orders(again))
    assertEquals(listOf(1L, 2L, 4L, 5L, 3L), unread(again))
    assertTrue(ShadowLog.getLogsForTag("ReadMe").none { it.msg.startsWith("store order numbered") })
  }

  @Test fun rowsAVersion1BuildWroteGoToTheBottomOnTheNextOpenAndMovesAreKept() {
    val s = store()
    val a = s.insertText("A", listOf("A."), 1000)
    val b = s.insertText("B", listOf("B."), 2000)
    assertTrue(s.move(b, Move.TOP))
    // A version-1 build installed over this one inserts without the column.
    val db = s.writableDatabase
    db.execSQL("INSERT INTO items (id, kind, title, created_at, state) VALUES (20, 'text', 'x', 50, 'ready')")
    db.execSQL("INSERT INTO items (id, kind, title, created_at, state) VALUES (21, 'text', 'x', 40, 'ready')")
    Store.resetForTest()
    assertEquals(listOf(b, a, 21L, 20L), unread(store()))
  }
}
