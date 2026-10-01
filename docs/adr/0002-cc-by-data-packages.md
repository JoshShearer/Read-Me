# ADR 0002: CC-BY-4.0 data-only packages are allowed

- Status: accepted
- Date: 2026-10-01
- Deciders: owner
- Amends: srs.md R-M13 ("all dependencies under OSI licenses"), AGENTS.md non-negotiable 14

## Context

React Native's production tree includes `caniuse-lite` (CC-BY-4.0) through
react-native -> @react-native/codegen -> @babel/core -> browserslist (found by
license-checker-rseidelsohn 5.0.1 on the RN 0.87 template, 2026-10-01). CC-BY-4.0 is not an OSI
software license, so a strict OSI rule fails every build of a stock React Native app.
`caniuse-lite` is browser-support data, not executable code that ships logic into the app.

## Decision

A production dependency must be OSI-licensed, except a data-only package under CC-BY-4.0 that
is listed **by name** in `DATA_EXCEPTIONS` in `scripts/check-licenses.mjs`. Adding a name to that
list is an owner decision recorded by amending this ADR.

## Consequences

- `scripts/check-licenses.mjs` reports every violation, not only the first.
- F-Droid's inclusion policy accepts free-culture data licenses. Re-confirm when the F-Droid
  metadata is written (roadmap Phase 6).
