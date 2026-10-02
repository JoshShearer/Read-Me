package io.loopstring.readme.store

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
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
        put("title", title)
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
          put("title", title)
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

  fun idsInState(state: String): List<Long> =
    readableDatabase.rawQuery("SELECT id FROM items WHERE state = ?", arrayOf(state)).use {
      val out = ArrayList<Long>(it.count)
      while (it.moveToNext()) out.add(it.getLong(0))
      out
    }

  /** Deletes the item, its paragraphs, cuts, position and any waiting body (R-M11, AGENTS.md 3). */
  fun delete(id: Long) {
    writableDatabase.delete("items", "id = ?", arrayOf(id.toString()))
    bodyFile(id).delete()
    ItemEvents.changed()
  }

  internal fun bodyFile(id: Long) = File(bodies, "$id.html")

  private fun writeParagraphs(db: SQLiteDatabase, id: Long, paragraphs: List<ParagraphRow>) {
    db.delete("paragraphs", "item_id = ?", arrayOf(id.toString()))
    val stmt = db.compileStatement(
      "INSERT INTO paragraphs (item_id, idx, kind, text) VALUES (?, ?, ?, ?)",
    )
    paragraphs.forEachIndexed { i, p ->
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
