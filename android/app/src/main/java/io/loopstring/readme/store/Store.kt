package io.loopstring.readme.store

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import io.loopstring.readme.playback.Segmenter
import java.io.File

data class ItemRow(
  val id: Long,
  val kind: String,
  val url: String?,
  val title: String,
  val site: String?,
  val byline: String?,
  val createdAt: Long,
  val state: String,
  val failReason: String?,
  val openedAt: Long?,
  val archivedAt: Long?,
)

data class ParagraphRow(val kind: String, val text: String)

/** R-M11: a character offset in a paragraph, never a sentence index (AGENTS.md 10). */
data class PositionRow(val paragraphIndex: Int, val charOffset: Int)

/** R-M01: the list's length estimate and progress, over kept paragraphs only. */
data class ItemStats(val words: Int, val keptChars: Int, val readChars: Int)

/** REA-63: what [Store.findUnreadLinkOrInsert] did: matched an unread item, or created one. */
data class LinkShare(val id: Long, val created: Boolean)

/** REA-72 (ADR 0017): a move of one unread item, relative to the stored List order at the write. */
enum class Move {
  TOP, UP, DOWN, BOTTOM;

  companion object {
    /**
     * The JS names, mapped explicitly. An unknown name throws without echoing it: the module's
     * rejection carries the class only, and the string never reaches a message.
     */
    fun of(name: String): Move = when (name) {
      "top" -> TOP
      "up" -> UP
      "down" -> DOWN
      "bottom" -> BOTTOM
      else -> throw IllegalArgumentException("unknown move")
    }
  }
}

object States {
  const val FETCHING = "fetching"
  const val FETCHED = "fetched"
  const val FETCH_FAILED = "fetch-failed"
  const val EXTRACT_POOR = "extract-poor"
  const val READY = "ready"
}

/**
 * The library (ADR 0001: Kotlin owns the database; JS goes through ReadMeSpeech). Item
 * lifecycle per ADR 0007. A fetched page's HTML is a file under files/bodies until JS extracts
 * it: a CursorWindow (2 MB) cannot hold a 5 MB body. Every write fires ItemEvents.changed().
 * Nothing here logs but [backfillAtStart] and the order numbering at open ([ensureOrderColumn]),
 * with counts, a duration and a class name only.
 */
class Store private constructor(context: Context) :
  SQLiteOpenHelper(context, DB_NAME, null, VERSION) {

  private val bodies = File(context.filesDir, "bodies")

  override fun onConfigure(db: SQLiteDatabase) {
    db.setForeignKeyConstraintsEnabled(true)
  }

  override fun onCreate(db: SQLiteDatabase) {
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
    // Written by PlaybackService in Phase 3 (R-M11): a character offset, never a sentence.
    db.execSQL(
      """CREATE TABLE positions (
        item_id INTEGER PRIMARY KEY REFERENCES items(id) ON DELETE CASCADE,
        paragraph_index INTEGER NOT NULL,
        char_offset INTEGER NOT NULL,
        updated_at INTEGER NOT NULL)""",
    )
    ensureWordsColumn(db)
    ensureOrderColumn(db)
  }

  /**
   * ADR 0013: `paragraphs.words` was added without a version bump, so a store made by 1.0.x
   * gets the column here. Only this cheap schema step runs inside the open (it holds the
   * helper's lock); counting the old rows is [backfillWords]'s, on a worker, and until then
   * COALESCE in [stats] counts them as 0.
   */
  override fun onOpen(db: SQLiteDatabase) {
    if (db.isReadOnly) return
    ensureWordsColumn(db)
    ensureOrderColumn(db)
  }

  /**
   * REA-72 (ADR 0017): `items.list_order` is the unread List's stored order, added like
   * `words` without a version bump (a version-2 store would make every version-1 build throw in
   * onDowngrade). Every row without a value is numbered here, after the current maximum, oldest
   * first (created_at, then id): on a 1.0.x or 1.1.x store that is every row, so the List flips
   * to oldest on top once; afterwards only rows a version-1 build wrote over this one, which go
   * to the bottom and leave the user's moves alone. Numbered in Kotlin, not with ROW_NUMBER():
   * window functions need SQLite 3.25 (API 30) and minSdk is 24. One short scan of `items` (no
   * paragraph text) per open; nothing is written when no row lacks a value.
   */
  private fun ensureOrderColumn(db: SQLiteDatabase) {
    val has = db.rawQuery("PRAGMA table_info(items)", null).use {
      var found = false
      while (it.moveToNext()) if (it.getString(1) == "list_order") found = true
      found
    }
    if (!has) db.execSQL("ALTER TABLE items ADD COLUMN list_order INTEGER")
    val started = SystemClock.elapsedRealtime()
    var numbered = 0
    db.beginTransaction()
    try {
      val ids = db.rawQuery(
        "SELECT id FROM items WHERE list_order IS NULL ORDER BY created_at ASC, id ASC",
        null,
      ).use {
        val out = ArrayList<Long>(it.count)
        while (it.moveToNext()) out.add(it.getLong(0))
        out
      }
      if (ids.isNotEmpty()) {
        var next = nextOrder(db)
        val update = db.compileStatement("UPDATE items SET list_order = ? WHERE id = ?")
        for (id in ids) {
          update.clearBindings()
          update.bindLong(1, next++)
          update.bindLong(2, id)
          update.executeUpdateDelete()
        }
        update.close()
        numbered = ids.size
      }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
    if (numbered > 0) {
      Log.i("ReadMe", "store order numbered rows=$numbered ms=${SystemClock.elapsedRealtime() - started}")
    }
  }

  /** The bottom of the List: one past the largest value over every row, archived ones too. */
  private fun nextOrder(db: SQLiteDatabase): Long =
    db.rawQuery("SELECT COALESCE(MAX(list_order), 0) + 1 FROM items", null).use {
      it.moveToFirst()
      it.getLong(0)
    }

  private fun ensureWordsColumn(db: SQLiteDatabase) {
    val has = db.rawQuery("PRAGMA table_info(paragraphs)", null).use {
      var found = false
      while (it.moveToNext()) if (it.getString(1) == "words") found = true
      found
    }
    if (!has) db.execSQL("ALTER TABLE paragraphs ADD COLUMN words INTEGER")
    // The rows a backfill still has to count: every row a version-1 build wrote.
    db.execSQL("CREATE INDEX IF NOT EXISTS paragraphs_uncounted ON paragraphs(item_id) WHERE words IS NULL")
  }

  /**
   * ADR 0013: counts every paragraph with no stored count (a 1.0.x store, or rows a version-1
   * build wrote over this one) and returns how many it counted. Paged by rowid, one short
   * transaction per page, so a waiting writer (a share on the main thread) waits for one page
   * at most. Each row's text is read by its own one-row query: a cursor over many 500K-char
   * rows would refill its 2 MB window by re-running the query over every earlier row.
   */
  internal fun backfillWords(
    count: (String) -> Int = ::countWords,
    page: Int = BACKFILL_PAGE,
    onPage: () -> Unit = {},
  ): Int {
    val db = writableDatabase
    var total = 0
    var after = -1L
    while (true) {
      db.beginTransaction()
      try {
        val ids = db.rawQuery(
          "SELECT rowid FROM paragraphs WHERE words IS NULL AND rowid > ? ORDER BY rowid LIMIT ?",
          arrayOf(after.toString(), page.toString()),
        ).use {
          val out = ArrayList<Long>(page)
          while (it.moveToNext()) out.add(it.getLong(0))
          out
        }
        if (ids.isEmpty()) {
          db.setTransactionSuccessful()
          return total
        }
        onPage()
        val update = db.compileStatement("UPDATE paragraphs SET words = ? WHERE rowid = ?")
        for (rowid in ids) {
          val text = db.rawQuery("SELECT text FROM paragraphs WHERE rowid = ?", arrayOf(rowid.toString()))
            .use { if (it.moveToFirst()) it.getString(0) else null } ?: continue
          update.clearBindings()
          update.bindLong(1, count(text).toLong())
          update.bindLong(2, rowid)
          update.executeUpdateDelete()
          total++
        }
        update.close()
        after = ids.last()
        db.setTransactionSuccessful()
      } finally {
        db.endTransaction()
      }
    }
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    // Version 1 is the first schema; a later version adds its migration here. `words` (ADR
    // 0013) and `list_order` (ADR 0017) were added without a bump, so a migration must treat
    // either as possibly absent.
    throw IllegalStateException("no migration from $oldVersion to $newVersion")
  }

  /**
   * Tests only: a link insert that skips REA-63's match. Intake inserts links through
   * [findUnreadLinkOrInsert], so a production caller here would bring the duplicates back.
   */
  @VisibleForTesting
  internal fun insertLink(url: String, title: String, now: Long): Long {
    val db = writableDatabase
    val id: Long
    db.beginTransaction()
    try {
      id = db.insertOrThrow("items", null, linkValues(db, url, title, now))
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
    ItemEvents.changed()
    return id
  }

  /**
   * REA-63 (ADR 0016): a shared link that exactly matches an unread link item is that item,
   * whatever its state; otherwise a new fetching item. Exact means the string
   * Intake stored: no tracking parameter, fragment or trailing slash is stripped. An archived
   * match does not count (a second share of something read may be deliberate). Several unread
   * matches (duplicates from before this change): the newest. The lookup and the insert are one
   * transaction, so two shares at once cannot both insert. A match writes nothing and fires
   * nothing, so the matched item keeps its place in the List (ADR 0017); a new item goes to the
   * bottom.
   */
  fun findUnreadLinkOrInsert(url: String, title: String, now: Long): LinkShare {
    val db = writableDatabase
    val share: LinkShare
    db.beginTransaction()
    try {
      val found = db.rawQuery(
        "SELECT id FROM items WHERE kind = 'link' AND url = ? AND archived_at IS NULL ORDER BY id DESC LIMIT 1",
        arrayOf(url),
      ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
      share = if (found != null) {
        LinkShare(found, created = false)
      } else {
        LinkShare(db.insertOrThrow("items", null, linkValues(db, url, title, now)), created = true)
      }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
    if (share.created) ItemEvents.changed()
    return share
  }

  // Called inside the insert's transaction, so the bottom value and the insert are one write.
  private fun linkValues(db: SQLiteDatabase, url: String, title: String, now: Long) = ContentValues().apply {
    put("list_order", nextOrder(db))
    put("kind", "link")
    put("url", url)
    put("title", cap(title))
    put("created_at", now)
    put("state", States.FETCHING)
  }

  fun insertText(title: String, paragraphs: List<String>, now: Long): Long =
    insertText(title, paragraphs.map { ParagraphRow("p", it) }, now)

  /** R-C04: a shared file keeps its headings and list items. */
  @JvmName("insertTextRows")
  fun insertText(title: String, paragraphs: List<ParagraphRow>, now: Long): Long {
    val db = writableDatabase
    val id: Long
    db.beginTransaction()
    try {
      id = db.insertOrThrow(
        "items",
        null,
        ContentValues().apply {
          put("list_order", nextOrder(db)) // REA-72: a new item goes to the bottom
          put("kind", "text")
          put("title", cap(title))
          put("created_at", now)
          put("state", States.READY)
        },
      )
      writeParagraphs(db, id, paragraphs)
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
    ItemEvents.changed()
    return id
  }

  /** ADR 0007's JS drain reads only these: no paragraph is read and no stats are computed. */
  fun fetchedItems(): List<ItemRow> =
    readableDatabase.rawQuery(
      "SELECT * FROM items WHERE state = ? ORDER BY created_at DESC, id DESC",
      arrayOf(States.FETCHED),
    ).use {
      val out = ArrayList<ItemRow>(it.count)
      while (it.moveToNext()) out.add(it.toItem())
      out
    }

  fun item(id: Long): ItemRow? =
    readableDatabase.rawQuery("SELECT * FROM items WHERE id = ?", arrayOf(id.toString())).use {
      if (it.moveToFirst()) it.toItem() else null
    }

  /**
   * R-M01 as amended (REA-72, ADR 0017): unread items in the stored List order, then the
   * Archive newest-shared first (created_at DESC, id DESC, its order before REA-72). JS filters
   * these into the two views and never re-sorts them.
   */
  fun items(): List<ItemRow> =
    readableDatabase.rawQuery(
      """SELECT * FROM items ORDER BY (archived_at IS NOT NULL),
           CASE WHEN archived_at IS NULL THEN list_order END,
           CASE WHEN archived_at IS NULL THEN id END,
           created_at DESC, id DESC""",
      null,
    ).use {
      val out = ArrayList<ItemRow>(it.count)
      while (it.moveToNext()) out.add(it.toItem())
      out
    }

  /**
   * R-S05 as amended (REA-72, ADR 0017): the unarchived ready items below [id] in the stored
   * List order (list_order, then id), nearest first. [id] keeps its value when archived, so an
   * item that just finished, or one played from the Archive, hands over from its own place.
   * Never wraps to the top; empty when [id] is gone. Ids only.
   */
  fun unreadAfter(id: Long): List<Long> =
    readableDatabase.rawQuery(
      """SELECT i.id FROM items i, (SELECT list_order AS o, id AS d FROM items WHERE id = ?) f
         WHERE i.archived_at IS NULL AND i.state = ?
           AND (i.list_order > f.o OR (i.list_order = f.o AND i.id > f.d))
         ORDER BY i.list_order, i.id""",
      arrayOf(id.toString(), States.READY),
    ).use {
      val out = ArrayList<Long>(it.count)
      while (it.moveToNext()) out.add(it.getLong(0))
      out
    }

  fun paragraphs(id: Long): List<ParagraphRow> =
    readableDatabase.rawQuery(
      "SELECT kind, text FROM paragraphs WHERE item_id = ? ORDER BY idx",
      arrayOf(id.toString()),
    ).use {
      val out = ArrayList<ParagraphRow>(it.count)
      while (it.moveToNext()) out.add(ParagraphRow(it.getString(0), it.getString(1)))
      out
    }

  fun cuts(id: Long): List<Int> =
    readableDatabase.rawQuery(
      "SELECT paragraph_index FROM cuts WHERE item_id = ? ORDER BY paragraph_index",
      arrayOf(id.toString()),
    ).use {
      val out = ArrayList<Int>(it.count)
      while (it.moveToNext()) out.add(it.getInt(0))
      out
    }

  /**
   * One aggregate query, so the list never loads item text (a paragraph can be 500K chars).
   * Words are each paragraph's stored count, the runs outside JS `\s` (ADR 0013; a row the
   * backfill has not reached yet counts 0); read is every kept character before the saved
   * position. SQLite counts characters, JS offsets count UTF-16 units: an emoji-heavy text's
   * progress is off by a little, which an estimate tolerates.
   */
  fun stats(only: Long? = null): Map<Long, ItemStats> =
    readableDatabase.rawQuery(
      STATS_SQL + (if (only == null) "" else " AND p.item_id = ?") + " GROUP BY p.item_id",
      if (only == null) null else arrayOf(only.toString()),
    ).use {
      val out = HashMap<Long, ItemStats>(it.count)
      while (it.moveToNext()) out[it.getLong(0)] = ItemStats(it.getInt(1), it.getInt(2), it.getInt(3))
      out
    }

  /** R-M05: replaces the item's whole cut set at once ("Cut everything after this"). */
  fun setCuts(id: Long, indices: List<Int>) {
    val db = writableDatabase
    db.beginTransaction()
    try {
      db.delete("cuts", "item_id = ?", arrayOf(id.toString()))
      for (i in indices.distinct()) {
        db.execSQL("INSERT INTO cuts (item_id, paragraph_index) VALUES (?, ?)", arrayOf<Any>(id, i))
      }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
    ItemEvents.changed()
  }

  fun idsInState(state: String, createdBefore: Long = Long.MAX_VALUE): List<Long> =
    readableDatabase.rawQuery(
      "SELECT id FROM items WHERE state = ? AND created_at < ?",
      arrayOf(state, createdBefore.toString()),
    ).use {
      val out = ArrayList<Long>(it.count)
      while (it.moveToNext()) out.add(it.getLong(0))
      out
    }

  /** R-M03 to ADR 0007: the body is on disk before the state says it is. */
  fun fetchSucceeded(id: Long, html: String) {
    bodies.mkdirs()
    val tmp = File(bodies, "$id.html.tmp")
    tmp.writeText(html, Charsets.UTF_8)
    if (!tmp.renameTo(bodyFile(id))) throw IllegalStateException("body rename failed")
    // Deleted (or no longer fetching) while the request ran: nothing will ever read this body.
    if (!setStateIf(id, States.FETCHING, States.FETCHED, null)) bodyFile(id).delete()
  }

  fun fetchFailed(id: Long, reason: String) {
    setStateIf(id, States.FETCHING, States.FETCH_FAILED, reason)
  }

  /** The user's Retry (R-M03 as amended): only a failed fetch goes back to fetching. */
  fun beginRetry(id: Long): Boolean = setStateIf(id, States.FETCH_FAILED, States.FETCHING, null)

  /**
   * The HTML of a fetched item, for JS extraction. A fetched item whose body is missing cannot
   * be extracted, so it becomes fetch-failed: interrupted instead of waiting forever.
   */
  fun bodyOrFail(id: Long): String? {
    if (item(id)?.state != States.FETCHED) return null
    val file = bodyFile(id)
    if (!file.exists()) {
      setStateIf(id, States.FETCHED, States.FETCH_FAILED, "interrupted")
      return null
    }
    return file.readText(Charsets.UTF_8)
  }

  /** R-M04: stores the extracted structure and discards the raw HTML. Only from fetched. */
  fun completeExtraction(
    id: Long,
    title: String,
    site: String?,
    byline: String?,
    paragraphs: List<ParagraphRow>,
    poor: Boolean,
  ): Boolean {
    val db = writableDatabase
    db.beginTransaction()
    try {
      val updated = db.update(
        "items",
        ContentValues().apply {
          put("title", cap(title))
          put("site", cap(site))
          put("byline", cap(byline))
          put("state", if (poor) States.EXTRACT_POOR else States.READY)
          putNull("fail_reason")
        },
        "id = ? AND state = ?",
        arrayOf(id.toString(), States.FETCHED),
      )
      if (updated == 0) return false
      writeParagraphs(db, id, paragraphs)
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
    bodyFile(id).delete()
    ItemEvents.changed()
    return true
  }

  /** ADR 0007: Trim opens automatically while openedAt is unset (R-M05). */
  fun markOpened(id: Long, now: Long) {
    writableDatabase.execSQL(
      "UPDATE items SET opened_at = ? WHERE id = ? AND opened_at IS NULL",
      arrayOf<Any>(now, id),
    )
    ItemEvents.changed()
  }

  /** R-M05, AGENTS.md 12: a cut is a row; the paragraph text is never touched. */
  fun setCut(id: Long, paragraphIndex: Int, cut: Boolean) {
    if (cut) {
      writableDatabase.execSQL(
        "INSERT OR IGNORE INTO cuts (item_id, paragraph_index) VALUES (?, ?)",
        arrayOf<Any>(id, paragraphIndex),
      )
    } else {
      writableDatabase.delete(
        "cuts",
        "item_id = ? AND paragraph_index = ?",
        arrayOf(id.toString(), paragraphIndex.toString()),
      )
    }
    ItemEvents.changed()
  }

  /** R-M11 as amended (ADR 0007): archive is a timestamp; the state is kept. */
  fun archive(id: Long, now: Long) {
    writableDatabase.execSQL("UPDATE items SET archived_at = ? WHERE id = ?", arrayOf<Any>(now, id))
    ItemEvents.changed()
  }

  /** REA-72 (ADR 0017): a restored item goes to the bottom; restoring an unread one changes nothing. */
  fun restore(id: Long) {
    writableDatabase.execSQL(
      "UPDATE items SET archived_at = NULL, " +
        "list_order = (SELECT COALESCE(MAX(list_order), 0) + 1 FROM items) " +
        "WHERE id = ? AND archived_at IS NOT NULL",
      arrayOf<Any>(id),
    )
    ItemEvents.changed()
  }

  /**
   * REA-72 (ADR 0017): moves one unread item within the unread List, relative to the stored
   * order at the moment of the write, so a stale view or two quick taps cannot put it somewhere
   * the user did not see. The unread rows' existing values are permuted (archived rows keep
   * theirs, so an item played from the Archive still hands over from its own place) and only
   * changed rows are written, in one transaction. False, with nothing written or fired, when
   * the item is missing, archived, or already at that edge.
   */
  fun move(id: Long, to: Move): Boolean {
    val db = writableDatabase
    var moved = false
    db.beginTransaction()
    try {
      var rows = unreadOrder(db)
      if (rows.map { it.second }.toSet().size != rows.size) {
        // Duplicate values should not exist; renumber every row in its current order first.
        renumberAll(db)
        rows = unreadOrder(db)
      }
      val ids = rows.map { it.first }
      val from = ids.indexOf(id)
      if (from >= 0) {
        val target = when (to) {
          Move.TOP -> 0
          Move.UP -> from - 1
          Move.DOWN -> from + 1
          Move.BOTTOM -> ids.size - 1
        }
        if (target in ids.indices && target != from) {
          val order = ids.toMutableList()
          order.removeAt(from)
          order.add(target, id)
          val old = rows.toMap()
          val update = db.compileStatement("UPDATE items SET list_order = ? WHERE id = ?")
          for ((i, row) in order.withIndex()) {
            val value = rows[i].second // the i-th smallest value goes to the i-th row
            if (old[row] == value) continue
            update.clearBindings()
            update.bindLong(1, value)
            update.bindLong(2, row)
            update.executeUpdateDelete()
          }
          update.close()
          moved = true
        }
      }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
    if (moved) ItemEvents.changed()
    return moved
  }

  private fun unreadOrder(db: SQLiteDatabase): List<Pair<Long, Long>> =
    db.rawQuery(
      "SELECT id, list_order FROM items WHERE archived_at IS NULL ORDER BY list_order, id",
      null,
    ).use {
      val out = ArrayList<Pair<Long, Long>>(it.count)
      while (it.moveToNext()) out.add(it.getLong(0) to it.getLong(1))
      out
    }

  private fun renumberAll(db: SQLiteDatabase) {
    val ids = db.rawQuery("SELECT id FROM items ORDER BY list_order, id", null).use {
      val out = ArrayList<Long>(it.count)
      while (it.moveToNext()) out.add(it.getLong(0))
      out
    }
    for ((i, rowId) in ids.withIndex()) {
      db.execSQL("UPDATE items SET list_order = ? WHERE id = ?", arrayOf<Any>(i + 1L, rowId))
    }
  }

  /**
   * R-M11: written by PlaybackService after every sentence and on pause. False when the item is
   * gone (deleted while it played): the foreign key refuses the row. No ItemEvents: a list
   * refresh per sentence would be waste, and the list does not show positions.
   */
  fun savePosition(id: Long, paragraphIndex: Int, charOffset: Int, now: Long): Boolean =
    try {
      writableDatabase.execSQL(
        "INSERT OR REPLACE INTO positions (item_id, paragraph_index, char_offset, updated_at) " +
          "VALUES (?, ?, ?, ?)",
        arrayOf<Any>(id, paragraphIndex, charOffset, now),
      )
      true
    } catch (e: SQLiteConstraintException) {
      false
    }

  fun position(id: Long): PositionRow? =
    readableDatabase.rawQuery(
      "SELECT paragraph_index, char_offset FROM positions WHERE item_id = ?",
      arrayOf(id.toString()),
    ).use { c -> if (c.moveToFirst()) PositionRow(c.getInt(0), c.getInt(1)) else null }

  /**
   * R-M11, ADR 0007: the last kept sentence finished. Archive (state kept) and drop the
   * position, so reopening the item starts from the top rather than past its end.
   */
  fun finishReading(id: Long, now: Long) {
    val db = writableDatabase
    db.beginTransaction()
    try {
      db.execSQL("UPDATE items SET archived_at = ? WHERE id = ?", arrayOf<Any>(now, id))
      db.delete("positions", "item_id = ?", arrayOf(id.toString()))
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
    ItemEvents.changed()
  }

  private fun setStateIf(id: Long, from: String, to: String, reason: String?): Boolean {
    val n = writableDatabase.update(
      "items",
      ContentValues().apply {
        put("state", to)
        if (reason == null) putNull("fail_reason") else put("fail_reason", reason)
      },
      "id = ? AND state = ?",
      arrayOf(id.toString(), from),
    )
    if (n > 0) ItemEvents.changed()
    return n > 0
  }

  /** Deletes the item, its paragraphs, cuts, position and any waiting body (R-M11, AGENTS.md 3). */
  fun delete(id: Long) {
    writableDatabase.delete("items", "id = ?", arrayOf(id.toString()))
    bodyFile(id).delete()
    File(bodies, "$id.html.tmp").delete()
    ItemEvents.changed()
  }

  internal fun bodyFile(id: Long) = File(bodies, "$id.html")

  private fun writeParagraphs(db: SQLiteDatabase, id: Long, paragraphs: List<ParagraphRow>) {
    db.delete("paragraphs", "item_id = ?", arrayOf(id.toString()))
    val stmt = db.compileStatement(
      "INSERT INTO paragraphs (item_id, idx, kind, text, words) VALUES (?, ?, ?, ?, ?)",
    )
    paragraphs.flatMap { p -> splitLong(p.text).map { ParagraphRow(p.kind, it) } }.forEachIndexed { i, p ->
      stmt.clearBindings()
      stmt.bindLong(1, id)
      stmt.bindLong(2, i.toLong())
      stmt.bindString(3, p.kind)
      stmt.bindString(4, p.text)
      stmt.bindLong(5, countWords(p.text).toLong())
      stmt.executeInsert()
    }
  }

  private fun Cursor.str(col: String): String? =
    getColumnIndexOrThrow(col).let { if (isNull(it)) null else getString(it) }

  private fun Cursor.long(col: String): Long? =
    getColumnIndexOrThrow(col).let { if (isNull(it)) null else getLong(it) }

  private fun Cursor.toItem() = ItemRow(
    id = long("id")!!,
    kind = str("kind")!!,
    url = str("url"),
    title = str("title")!!,
    site = str("site"),
    byline = str("byline"),
    createdAt = long("created_at")!!,
    state = str("state")!!,
    failReason = str("fail_reason"),
    openedAt = long("opened_at"),
    archivedAt = long("archived_at"),
  )

  companion object {
    private const val DB_NAME = "readme.db"
    private const val VERSION = 1
    // A row over Android's 2 MB CursorWindow throws on every read of its table, which would hide
    // the whole library behind one hostile page. Titles are capped; long paragraphs are split.
    const val MAX_FIELD_CHARS = 1_000
    const val MAX_PARAGRAPH_CHARS = 500_000 // at most 1.5 MB of UTF-8
    private const val BACKFILL_PAGE = 64

    // Kept flat: a deeply nested expression overflows SQLite's parser stack (REA-50's probe
    // failed at 30 nested replace calls), and StoreWordsTest bounds its depth.
    internal const val STATS_SQL =
      """SELECT p.item_id,
           SUM(COALESCE(p.words, 0)),
           SUM(length(p.text)),
           SUM(CASE WHEN pos.item_id IS NULL THEN 0
                    WHEN p.idx < pos.paragraph_index THEN length(p.text)
                    WHEN p.idx = pos.paragraph_index THEN MIN(pos.char_offset, length(p.text))
                    ELSE 0 END)
         FROM paragraphs p
         LEFT JOIN cuts c ON c.item_id = p.item_id AND c.paragraph_index = p.idx
         LEFT JOIN positions pos ON pos.item_id = p.item_id
         WHERE c.item_id IS NULL"""

    /**
     * R-M01, ADR 0013: the words in [s] are its runs of characters outside JS `\s`, so the
     * List's estimate matches the text pipeline's idea of a space. NUL and surrogate halves are
     * word characters, as in JS.
     */
    internal fun countWords(s: String): Int {
      var n = 0
      var inWord = false
      for (c in s) {
        if (Segmenter.isSpace(c)) {
          inWord = false
        } else if (!inWord) {
          inWord = true
          n++
        }
      }
      return n
    }

    /**
     * ADR 0013: the one-time count of rows written before `words` existed, run by
     * MainApplication on a worker at process start. A failure must never make the store
     * unusable: it is logged by class only (AGENTS.md 1), the rows stay NULL (counted as 0) and
     * the next process start tries again. True when it finished.
     */
    fun backfillAtStart(context: Context, count: (String) -> Int = ::countWords): Boolean =
      try {
        val started = SystemClock.elapsedRealtime()
        val rows = get(context).backfillWords(count)
        if (rows > 0) {
          Log.i("ReadMe", "store words backfilled rows=$rows ms=${SystemClock.elapsedRealtime() - started}")
        }
        true
      } catch (t: Throwable) {
        Log.w("ReadMe", "store words backfill failed: ${t.javaClass.simpleName}")
        false
      }

    internal fun cap(s: String?, max: Int = MAX_FIELD_CHARS): String? =
      if (s == null || s.length <= max) s else s.substring(0, safeEnd(s, max))

    /** Pieces of at most [max] chars that concatenate back to [text], cut after whitespace when possible. */
    internal fun splitLong(text: String, max: Int = MAX_PARAGRAPH_CHARS): List<String> {
      if (text.length <= max) return listOf(text)
      val out = ArrayList<String>()
      var start = 0
      while (text.length - start > max) {
        var end = start + max
        val ws = (end - 1 downTo start + max / 2).firstOrNull { text[it].isWhitespace() }
        end = if (ws != null) ws + 1 else safeEnd(text, end)
        out.add(text.substring(start, end))
        start = end
      }
      out.add(text.substring(start))
      return out
    }

    // Never cut between the halves of a surrogate pair.
    private fun safeEnd(s: String, end: Int) =
      if (end > 0 && end < s.length && Character.isHighSurrogate(s[end - 1])) end - 1 else end

    @Volatile private var instance: Store? = null

    fun get(context: Context): Store =
      instance ?: synchronized(this) {
        instance ?: Store(context.applicationContext).also { instance = it }
      }

    /** Robolectric gives each test a fresh app directory; drop the one bound to the last. */
    @VisibleForTesting
    fun resetForTest() {
      synchronized(this) {
        instance?.close()
        instance = null
      }
    }
  }
}
