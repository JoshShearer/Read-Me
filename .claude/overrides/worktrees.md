# /worktrees override - Read Me

Rules: `AGENTS.md` "Device work: the one-phone slot". `scripts/lib/device.sh` implements the lock.

`device REA-N` is an alias for `take REA-N`.

## The slot: who the phone is running

- **Install step**: `npm run device:install` (refuses an APK not stamped from HEAD; writes the
  marker). Prerequisite: the script exists and `adb devices` shows exactly one device.
- **Marker**: `$PRIMARY/.claude/scratch/device-installed-from` (lock-owner format plus
  `tree=clean|dirty`). Its commit is the APK stamp's, not HEAD's. It outlives the lock.
- **Ownership check** (needs exactly one device):

```bash
N=$(adb devices | awk 'NR>1 && $2=="device"' | wc -l)
[ "$N" -eq 1 ] || { echo "SLOT: $N devices attached; need exactly one"; exit 0; }
APP_ID=$(grep -oE 'applicationId[ =]+"[^"]+"' "$PRIMARY/android/app/build.gradle"* | head -1 | grep -oE '"[^"]+"' | tr -d '"')
S=$(mktemp -d)
P=$(adb shell pm path "$APP_ID" | sed -n 's/^package://p' | grep base.apk)
[ -n "$P" ] || { echo "SLOT: $APP_ID not installed"; exit 0; }
adb pull "$P" "$S/installed.apk" >/dev/null
shopt -s nullglob
for d in "$PRIMARY" "${PRIMARY}"-rea-* "${PRIMARY}"-run-*; do
  for apk in "$d"/android/app/build/outputs/apk/*/*.apk; do
    cmp -s "$apk" "$S/installed.apk" && echo "OWNS SLOT: $d ($apk)"
  done
done
rm -rf "$S"
```

  No match: the phone runs a build no worktree has on disk. If the `applicationId` line in
  `android/app/build.gradle` changes form, fix this block.

## Create

- Step 5: copy `android/local.properties` (`sdk.dir`) from the primary. Never copy a keystore or
  signing properties.
- Step 6: `npm ci` only if `package.json` exists. The first Gradle gate is slow (no build cache).
- Branch kinds: `feature/`, `fix/`, `spike/`.

## Take

Step 5: on a signature mismatch, stop. Never `adb uninstall`, `pm clear` or reinstall-with-wipe:
it deletes the owner's library on their own phone (non-negotiable 3 keeps it app-private, so there
is no other copy).

## Remove

Spike worktree: warn on probe output not yet archived under `$PRIMARY/.claude/scratch/`. The probe
code is throwaway; its raw measurements are not (rule 17).

## Conflicts: high-collision files

| File | Why |
|---|---|
| `android/app/src/main/AndroidManifest.xml` | Permissions, `allowBackup`, `TTS_SERVICE` query, FGS type; one dropped line breaks a non-negotiable |
| `android/app/build.gradle*` | A clean merge can add a non-F-Droid-clean transitive dependency (14) |
| `ReadMeSpeech` module, `PlaybackService` | JS-to-native contract and the queue that owns playback (11) |
| `BridgeServer` | Two edits can each keep token-before-body and break it together (8) |
| `Fetcher` | Sole network call site and its limits (6, 7) |
| `Store` / schema | Two migrations collide by version number |
| `src/segment/` | Character-offset positions (10) |
