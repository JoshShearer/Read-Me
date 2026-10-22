# ADR 0016: A shared link that matches an unread item opens that item

Date: 2026-10-22. Status: accepted. Issue: REA-63. Deciders: the owner (option 2, Linear
comment 2026-10-22) and the pipeline's triage for REA-63 (five recorded clarifications).
Amends: srs.md R-M02.

## Context

A community device test of 1.1.0 on fdroiddata !51088 (2026-10-21) shared the same URL twice
and got two items. R-M02 made every link share a new item and returned the user to the source
app, so a second share of something already in the list added a duplicate that was fetched
again. A second share may also mean "fetch it again", and a second share of an item already
read may be deliberate.

## Decision

- A link share whose URL is exactly the string Intake stores for an **unread** link item
  (`kind = 'link' AND url = ? AND archived_at IS NULL`) is that item. No tracking parameter,
  fragment or trailing slash is stripped. Several unread matches (duplicates from before this
  change): the newest (highest id). Nothing is merged or deleted.
- `Store.findUnreadLinkOrInsert` runs the lookup and the insert in one transaction and returns
  `(id, created)`. No schema change, no index.
- A match inserts nothing and queues no fetch, whatever the item's state, `fetching` and
  `fetch-failed` included. Retry stays an explicit user action (AGENTS.md 6).
- On a match `ShareActivity` toasts "Already in your list" and starts `MainActivity`
  (`singleTask`) with only the item id as an extra, never the URL (AGENTS.md 1). Its one log line
  is `share matched item=<id>`. `MainActivity` leaves the id in a one-shot slot (`SharedOpen`),
  ignoring an intent replayed by recreation or recents; JS takes it through `takeSharedOpen` on
  mount, on return to the foreground and on the `ReadMeSharedOpen` event.
- JS opens a `ready` or `extract-poor` match as the List's Read does: Trim on the first open,
  else the Reader, and `markOpened`. Any other state (`fetching`, `fetched`, `fetch-failed`), or an
  item deleted or archived since the share, shows the List's unread tab, where the row keeps its
  own actions (Retry). The open replaces the route stack and never plays, pauses or resumes
  (AGENTS.md 11).
- A link matching only an archived item is a new item, as before. Shared text and shared files
  are never matched.

## Rejected

- Keeping duplicates, and asking the user: the owner chose option 2.
- Matching in JS: `ShareActivity` runs with no JS runtime, and the owner put matching in the Store.
- Normalising URLs: the owner chose an exact match.
- Re-fetching a failed match: Retry is the user's; a share must not start network work for an
  item that has it.
- Putting the URL in the intent: it would reach the activity manager (AGENTS.md 1).
- Lifting the List's tab state into App: it would change Back-from-Trim; the List is remounted
  instead (a new key), which shows its unread tab.

## Consequences

- R-M02's "returns the user to the source app" has an exception: a match brings Read Me to the
  front. Starting `MainActivity` from the translucent share activity is a start from a foreground
  activity; a ROM that refused it would leave the toast and no open.
- Variants of a URL (tracking parameters, a fragment, a trailing slash) still make new items.
- Duplicates stored before this change are kept; the newest is the one a share opens.
- A shared open replaces whatever screen was open (Settings, another item's Reader).
