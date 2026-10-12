# /run-tickets override - Read Me

The facts are in `.claude/project.md` `## Autonomous runs`. These are the steps no other project
has. Subagent prompts get the sections their phase names, verbatim.

## Facts

| Fact | Consequence |
|---|---|
| **A tag is a release.** `fdroid/io.loopstring.readme.yml` has `UpdateCheckMode: Tags` and `AutoUpdateMode: Version`, so F-Droid builds and publishes every new `v*` tag by itself, and a GitHub release creates its tag. | The shared `guard` covers tags and releases. Release signing needs `-PreadmeSign=release` plus the owner's `~/.gradle/gradle.properties`; `build:release` is debug-signed and the only build a run makes. |
| **The phone is the owner's own, with their library on it, app-private** (non-negotiable 3). Most `device:*` acceptance scripts begin with `device_clear_app` (`pm clear`), which destroys that library for good. | `scripts/lib/device.sh` refuses `device_clear_app` (exit 7) in any `-run-<stamp>` worktree unless `$PRIMARY/.claude/device-data-disposable` exists, a file no ticket branch can create. A ticket could edit that guard, so `scripts/lib/device.sh` is a target guard file. A signature-mismatch install failure blocks the ticket. |
| **Installing replaces the app; the bridge checks also take host port 8787 with `adb forward`.** | Device scripts reuse a lock held by this lane on this branch. |
| **Linear is `linear-rea`, team `REA`, no `In Review` status.** | Never call `linear-nrl`, which is another workspace. |
| **The plugin side of the bridge is NRL-130**, in the plugin's own workspace. | A ticket that needs plugin changes blocks. Never commit to `note-reader-local`. |
| **CI is the `ci` workflow** (jobs `js`, `android`). | The workflow name for "Reading a CI conclusion" is `ci`. |

## Step 4: lane setup

After `npm ci`, copy (never link) from the primary, when present:

```bash
[ -f "$PRIMARY/android/local.properties" ] && cp "$PRIMARY/android/local.properties" android/
[ -f "$PRIMARY/mise.local.toml" ] && cp "$PRIMARY/mise.local.toml" .   # the JDK for Gradle and apksigner
```

Without `mise.local.toml` the mise `java` shim fails with "No version is set", and Gradle and
`test:scripts`' signing fixture fail with it. Check both copies before blaming `origin/main` for a
red baseline. Never copy a keystore or signing properties.

## Phase 0: Triage

Also block: a ticket needing a second phone, a non-GrapheneOS device or iOS; a release (a version
bump, a tag, the F-Droid recipe's `commit:` lines, signing). `needsTarget` also covers a ticket
that needs Obsidian with `local-tts-reader` on the phone; say which.

## Build and Fix: gates

After a dependency change, also `npm run notices` and `AGENTS.md` rule 14's resolved-tree check.
A spike's answer goes in `srs.md` "Spikes" with date, device, build and numbers that all appear in
the raw output under `<archive>`. The PR body's Manual Test Plan names exact steps over the share
sheet, Trim, Reader, the lock screen and the bridge.

## Critic: must-BLOCK

Item text or the bridge token reaching a log; a network call outside `Fetcher`; bridge input read
before the token check; rate applied twice; a position stored as a sentence index; a non-free
dependency entering the tree; anything touching the phone's data.

## Taking the target: the phone

Preconditions, then the lock:

```bash
N=$(adb devices | awk 'NR>1 && $2=="device"' | wc -l)
[ "$N" -eq 1 ] || echo "NOT VERIFIED ON DEVICE: not run ($N devices attached; need exactly one)"
```

Then `READ_ME_ABIS=arm64-v8a npm run build:release` (Verify runs it always: it is also the gate
that checks the notices asset), `npm run device:install` and `npm run device:smoke`: these never
clear data. Every other `device:*` script clears it and exits 7 in a lane unless `target-check`
reported `optIn: true`; record that as `NOT VERIFIED ON DEVICE: not run (would clear the owner's
app data)`. Exit 5 (phone locked) → `not run (phone locked)`; never wait for an unlock. Never
`GAP_MINUTES` above the default. Bridge checks need Obsidian with `local-tts-reader` on the phone;
if absent, that check is not run; never install, enable or reconfigure the plugin. Probes read
logcat filtered to the app's own tags (`AGENTS.md` Identifiers), and test inputs are synthetic
text, never the owner's library. The device line records `adb shell getprop ro.product.model`, the
serial and the build sha.

## Verify

Also, when dependencies changed, `AGENTS.md` rule 14's resolved-tree check. A spike: every number
in its `srs.md` diff appears in `<archive>`.

## Finish

A requirement is met only with a device observation named by date, device and build (`srs.md`
R-M14). Changes to `AGENTS.md` "Known state" or `srs.md` go to the report for `/update-docs`.
