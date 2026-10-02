# ADR 0007: Item lifecycle gains `fetched`, `openedAt` and `archivedAt`

- Status: accepted
- Date: 2026-10-02
- Deciders: owner (delegated, REA-14), from roadmap findings F12 and F15
- Amends: srs.md R-M02, R-M03, R-M04, R-M11, "Data model"

## Context

F12: the fetch runs in native code that outlives the share activity (R-M02, R-M03), but
extraction runs in JS (R-M04, ADR 0006). A fetch can finish while no JS runtime is alive. With
only `fetching` before `ready`, start-up recovery would mark that successful fetch
`fetch-failed: interrupted`, and the body would have nowhere to wait.

F15: two rules had no field to stand on. "Trim opens automatically the first time" (R-M05)
needs to know whether an item was ever opened. And `archived` as a state overwrote the item's
prior state, so restoring could not tell `ready` from `extract-poor`.

## Decision

- New state `fetched`: the `Fetcher` writes the body to app-private storage and moves the item
  to `fetched`. JS extracts every `fetched` item on each start and whenever it is running when
  a fetch completes; extraction moves the item to `ready` or `extract-poor` and deletes the
  stored body in the same transaction. Start-up recovery touches only `fetching`.
- `Item.openedAt?`: set the first time the item is opened; Trim opens automatically while it
  is unset.
- Archive is a field, `Item.archivedAt?`, not a state. Restore clears it and the item keeps the
  state it had. The list's Archive tab is items with `archivedAt` set.

## Consequences

- R-M04's "raw HTML MUST be discarded after extraction" holds: the body exists only between
  fetch and extraction, app-private, and is deleted with the item.
- A body up to 5 MB can sit on disk until the next app start; deleting the item deletes it.
- The Store schema (Phase 2) carries `state` without `archived`, plus the two timestamps.
