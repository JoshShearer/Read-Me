package io.loopstring.readme.store

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.annotation.VisibleForTesting
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
 * Nothing here logs.
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
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    // Version 1 is the first schema; a later version adds its migration here.
    throw IllegalStateException("no migration from $oldVersion to $newVersion")
  }

  fun insertLink(url: String, title: String, now: Long): Long {
    val id = writableDatabase.insertOrThrow(
      "items",
      null,
      ContentValues().apply {
        put("kind", "link")
        put("url", url)
        put("title", cap(title))
        put("created_at", now)
        put("state", States.FETCHING)
      },
    )
    ItemEvents.changed()
    return id
  }

  fun insertText(title: String, paragraphs: List<String>, now: Long): Long {
    val db = writableDatabase
    val id: Long
    db.beginTransaction()
    try {
      id = db.insertOrThrow(
        "items",
        null,
        ContentValues().apply {
          put("kind", "text")
          put("title", cap(title))
          put("created_at", now)
          put("state", States.READY)
        },
      )
      writeParagraphs(db, id, paragraphs.map { ParagraphRow("p", it) })
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
    ItemEvents.changed()
    return id
  }

  fun item(id: Long): ItemRow? =
    readableDatabase.rawQuery("SELECT * FROM items WHERE id = ?", arrayOf(id.toString())).use {
      if (it.moveToFirst()) it.toItem() else null
    }

  fun items(): List<ItemRow> =
    readableDatabase.rawQuery("SELECT * FROM items ORDER BY created_at DESC, id DESC", null).use {
      val out = ArrayList<ItemRow>(it.count)
      while (it.moveToNext()) out.add(it.toItem())
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

  fun restore(id: Long) {
    writableDatabase.execSQL("UPDATE items SET archived_at = NULL WHERE id = ?", arrayOf<Any>(id))
    ItemEvents.changed()
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
      "INSERT INTO paragraphs (item_id, idx, kind, text) VALUES (?, ?, ?, ?)",
    )
    paragraphs.flatMap { p -> splitLong(p.text).map { ParagraphRow(p.kind, it) } }.forEachIndexed { i, p ->
      stmt.clearBindings()
      stmt.bindLong(1, id)
      stmt.bindLong(2, i.toLong())
      stmt.bindString(3, p.kind)
      stmt.bindString(4, p.text)
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
