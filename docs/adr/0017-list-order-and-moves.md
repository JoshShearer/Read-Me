# ADR 0017: The unread List is a stored queue order

Date: 2026-11-01. Status: accepted. Issue: REA-72. Deciders: the owner (REA-72's request) and the
pipeline's triage for REA-72 (six recorded clarifications). Amends: srs.md R-M01 and R-S05; ADR
0011's "Next item".

## Context

The List showed unread items newest first (`Store.items()`: `created_at DESC, id DESC`), and
R-S05's amendment of 2026-10-09 wrote that order into continuous play: the next item was the next
older one, and items shared during a chain were never reached. The owner wants a reading queue:
a new share goes to the bottom, and any unread item can be moved from any position to any other.
The order must survive a restart and a process kill, so it is stored, and the playback service
must read it with no JS (AGENTS.md 11), so it lives in the native `Store`.

## Decision

- **Column.** `items.list_order INTEGER`, nullable, added in `onCreate` and, when missing, in
  `onOpen` (`ensureOrderColumn`). The schema `VERSION` stays 1, as ADR 0013 did for
  `paragraphs.words`: a version-2 store opened by any version-1 build (1.1.0 from F-Droid, or
  another lane's build installed over this one) would throw in the default `onDowngrade` and the
  library could not open until its data was cleared. Version-1 code inserts with named columns
  and reads by column name, so it ignores the column.
- **Numbering at open.** Every row with no value is numbered after the current maximum, oldest
  first (`created_at ASC, id ASC`), in one transaction. On a 1.0.x or 1.1.x store that is every
  row, so the List flips once to oldest on top (clarification 1: the new rule applied as if
  every item had been added under it; keeping newest on top would put the first new share below
  the oldest item, a mixed order). Later it numbers only rows a version-1 build wrote over this
  one: they go to the bottom and the user's moves stay. Done in Kotlin, not with `ROW_NUMBER()`:
  window functions need SQLite 3.25 (API 30) and minSdk is 24. One log line when it numbered
  anything: `store order numbered rows=<n> ms=<t>` (counts only, AGENTS.md 1).
- **Values.** Unique integers, sparse after deletes, never renumbered densely. An insert (link,
  text or file) and a restore take `MAX(list_order) + 1` over all rows, archived ones included,
  in the same transaction as the write.
- **A REA-63 match writes nothing** (ADR 0016), so the matched item stays where it is
  (clarification 4).
- **Restore from Archive goes to the bottom** (clarification 2): its old neighbours may have
  moved, been archived or deleted. Restoring an item that is not archived changes nothing.
- **Moves are relative**: `Store.move(id, Move)` with `TOP`, `UP`, `DOWN`, `BOTTOM`, computed
  in one transaction from the stored order at the write, so a stale List view or two quick taps
  cannot put an item where the user did not see it. It permutes the unread rows' existing
  values (archived rows keep theirs) and writes only rows whose value changed. False, with
  nothing written and no change event, when the item is missing, archived, or already at that
  edge. Duplicate values (which should not exist) are first renumbered in their current order.
  JS calls `moveUnreadItem(id, 'top' | 'up' | 'down' | 'bottom')`; the name maps explicitly
  (`Move.of`) and an unknown one rejects with the class name only.
- **Order is over unread rows of every state** (fetching, failed, poor, ready): it is what the
  List shows. Continuous play still skips non-ready rows.
- **Archive** keeps its order before REA-72, `created_at DESC, id DESC` (newest shared first),
  and cannot be reordered (clarification 3; out of scope). `Store.items()` returns unread rows
  by `(list_order, id)` and then the Archive; JS filters and never re-sorts.
- **UI.** Each unread row has a Move button when the unread tab has two rows or more. It opens
  the app's bottom sheet (REA-62's `ActionSheet`, one element in the screen, switched by
  `visible`) with only the moves that change something. The same moves are the row's
  accessibility actions ("Move to top", "Move up", "Move down", "Move to bottom"), which
  TalkBack offers as custom actions. Dragging is not built (clarification 5): it needs a gesture
  library (AGENTS.md 14, the REA-28 precedent of no new UI dependency) or a hand-rolled list
  with autoscroll; the move actions reach any position.
- **Continuous play.** `Store.unreadAfter(id)` compares `(list_order, id)` against the finished
  item's own values: the next row down. An item keeps its value when archived, so a finished
  item, or one played from the Archive, hands over from its own place. The next item is
  evaluated at the handover (ADR 0011); a reorder alone never ends the chain, and an item
  prepared early (REA-49) that is no longer the next row down is discarded by the handover's
  confirmation (clarification 6).

## Rejected

- A version bump with `onUpgrade`: a downgrade throws and the store cannot open.
- Dense renumbering (1..n over unread rows): it collides with archived rows' values, so an item
  played from the Archive would hand over relative to a meaningless value.
- Fractional values (midpoints): precision runs out, and nothing gains at hundreds of rows.
- Index-based moves from JS (`moveTo(id, index)`): an index from a stale view lands wrong; it
  is only needed for dragging.
- Dragging now: see above; a follow-up ticket if wanted.
- Keeping newest on top for the items that existed before the upgrade.

## Consequences

- Existing users see the List flip to oldest on top once, at the first open of this version.
- Items shared or restored during a continuous chain are appended at the bottom and are
  reached if the chain gets there. An item moved above the one being read is not reached.
- A version-1 build installed after this one shows newest first, ignores moves, inserts rows
  with no value and restores without moving the item; when this code returns, those rows are
  numbered to the bottom and earlier moves are intact. No data is lost and the store opens.
- Each open scans `items` once for rows with no value (no paragraph text is read).
- TalkBack focus after a move is not managed beyond React Native's default.
- The next real schema migration must treat `list_order` as possibly absent, as ADR 0013 says
  for `words`.
- `npm run device:continuous` shares its test items at the bottom, below the owner's items:
  it scrolls to the bottom, requires the last four rows to be T3, T2, T1 and the buffer (the last
  row), expects each next test item to have a higher id, and its cleanup reads the whole view
  (unread and Archive) before it says done (ADR 0011's amendment of this date).
