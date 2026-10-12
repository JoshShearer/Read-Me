# Override: /finish - Read Me

## Step 2: unmerged spike branches

A `spike/*` branch is often never merged: its code is throwaway and what ships is the answer in
`srs.md` "Spikes". Before deleting an unmerged spike branch, confirm the answer is on
`origin/main`:

```bash
git show origin/main:srs.md | grep -n SPIKE-0N
```

Not there: **stop**. Deleting the branch may delete the only record of the measurement.

## Step 7: worktree residue and the phone

Dirty-tree residue after a merge is usually `node_modules`, `android/build/`,
`android/app/build/` and `.gradle/`. Still confirm before `--force`.

The lock is `.claude/device.lock/` (AGENTS.md "Device work: the one-phone slot").

Stale-build check, read-only:

```bash
adb devices
adb shell dumpsys package io.loopstring.readme | grep -E 'versionName|versionCode|lastUpdateTime'
```

(applicationId per `CONTEXT.md` "Identifiers".) Not matching merged `main`, or you cannot tell:
tell the user to run `npm run device:install` from the primary once `main` is synced, taking the
lock first. No phone attached: report the check as not run.

## Step 9: spike closing comment

For a spike, the closing comment also names the `srs.md` line where the answer was recorded.

## Step 10: Read Me specifics

- `AGENTS.md` "Known state" is the known-defects section; its spec-check baseline moves only on
  a device observation per R-M14 (date, device, build).
- A spike that answered its question: record it under `srs.md` "Spikes" with measurement, device
  and date; an ADR in `docs/adr/` too if it changes a decision.
