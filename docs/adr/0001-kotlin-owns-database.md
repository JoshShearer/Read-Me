# ADR 0001: Kotlin owns the database

- Status: accepted
- Date: 2026-10-01
- Deciders: owner
- Amends: srs.md "Method" (TypeScript modules table, Data model)

## Context

srs.md placed `library` (SQLite) in TypeScript. But three writers run while the JS runtime may
not be alive: share intake creates an item after the share activity finishes (R-M02), the
Fetcher completes in the background (R-M03), and PlaybackService saves the position after every
sentence and archives the item with the screen off (R-M07, R-M11). Roadmap finding F11.

## Decision

The Kotlin `Store` holds the only SQLite connection (platform `android.database.sqlite`, no
dependency). JS reads and writes only through `ReadMeSpeech`. The TS `library` module is a typed
facade over those calls.

## Consequences

- No npm SQLite library, so SPIKE-04 has no SQLite dependency to vet.
- Schema and migrations are Kotlin, unit-tested in Kotlin. TS tests of `library` test the facade
  against a fake module.
- Every background write path is native, which non-negotiable 11 already requires for playback.
