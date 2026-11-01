package io.loopstring.readme.store

import android.database.sqlite.SQLiteDatabase

/** Tests only: the exact version-1 schema released in 1.0.0 to 1.0.5 (no `words`, no `list_order`). */
internal object V1Schema {
  fun create(db: SQLiteDatabase) {
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
  }
}
