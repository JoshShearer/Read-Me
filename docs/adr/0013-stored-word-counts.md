# ADR 0013: Stored per-paragraph word counts, added without a schema version bump

Date: 2026-10-14. Status: accepted. Issue: REA-50. Deciders: the pipeline's triage for REA-50
(clarification: "Store it: paragraphs.words, counted in Kotlin with Segmenter.isSpace when
written, added by an additive nullable column and backfilled ... with no version bump").
Amends: ADR 0001's schema (the `paragraphs` table gains a column); `Store.stats()`.

## Context

The List's length estimate (R-M01) counted words in SQL as spaces plus one, so newlines, tabs
and other whitespace were not separators and repeated spaces added words. Counting runs of JS
`\s` in SQL needs nested `replace` calls: a flat chain of 30 overflows SQLite's parser stack
("parser stack overflow"), a split into three subquery levels prepares but cost 0.3177 s against
today's 0.0281 s on a 5 MB library, and SQLite's `length` stops at NUL, so it miscounts text
with a NUL in it (REA-50 host probe, SQLite 3.45.1 under Python: `.claude/runs/REA-50/probe_v2.out`
in the owner's checkout; host numbers, not device ones).

## Decision

- `paragraphs.words INTEGER`, nullable. `Store.countWords` counts the runs of characters that
  are not JS `\s` (`Segmenter.isSpace`) when a paragraph is written; `stats()` sums
  `COALESCE(words, 0)`. A test pins `countWords` to a literal JS `\s` list over every UTF-16
  unit, and another bounds the stats query's nesting.
- The column and a partial index `paragraphs_uncounted ON paragraphs(item_id) WHERE words IS
  NULL` are added in `onCreate` and, if missing, in `onOpen`. Only this schema step runs inside
  the open.
- Rows with no count (a 1.0.x store, or rows a version-1 build writes over this one) are counted
  by `Store.backfillAtStart`, which `MainApplication` runs on its own worker at every process
  start. It pages by rowid (64 rows per short transaction, each row's text read by a one-row
  query), so the open's lock is never held through it, a share on the main thread waits for at
  most one page, and no cursor window is refilled by re-running a query over earlier rows.
  It logs `store words backfilled rows=<n> ms=<t>` when it counted anything. A failure is logged
  as `store words backfill failed: <class>` only (AGENTS.md 1); the rows stay NULL and count 0,
  and the next process start tries again. It can never make the store unopenable.
- `VERSION` stays 1. A bump with `onUpgrade` was rejected: a version-2 store opened by any
  version-1 build (1.0.5, or another lane's build installed over this one on the one shared
  phone with the same versionCode) hits the default `onDowngrade`, which throws, and the store
  cannot open until its data is cleared. Version-1 code ignores the extra column: it inserts
  with named columns and reads `kind` and `text`.

## Consequences

- Displayed word counts of existing items change once the backfill has run: any item whose text
  holds newlines, tabs, NBSP, U+3000 or repeated spaces shows a different, more accurate count.
  Text shares were already collapsed to single spaces by `Intake`, so theirs do not change.
- Until the backfill reaches a row it counts 0, so an old item can briefly show a lower count
  right after the update.
- The schema has changed while `VERSION` says 1. The next real migration must treat `words` and
  `paragraphs_uncounted` as possibly absent (a store never opened by this code) or present.
- The count stays an estimate: `Store.splitLong` cuts long paragraphs after Kotlin
  `isWhitespace()`, which also accepts U+001C..U+001F (not JS `\s`), and makes a hard cut when a
  500K-char window has no whitespace in its second half; either can count one word twice.
- Rolling the code back is safe: version-1 code runs on a store that has `words`. Rows written
  meanwhile land NULL and are counted again if this change returns.
- The backfill's duration on a real library is not measured here; the verify phase reads the
  log line on the phone.
