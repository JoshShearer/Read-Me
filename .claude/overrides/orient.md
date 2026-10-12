# /orient override - Read Me

Rules: `AGENTS.md` "Device work: the one-phone slot", "Non-negotiables". Modules: `CONTEXT.md`.

## Step 1

- `package.json` absent: also skip every Gradle step.

## Step 4: what is on the phone

Read-only. Never run `npm run device:install` from here.

```bash
command -v adb >/dev/null || echo "ADB=not on PATH (try ~/Android/Sdk/platform-tools/adb)"
adb devices 2>/dev/null | awk 'NR>1 && $2=="device"' | wc -l | xargs echo "DEVICES_ATTACHED="
APP_ID=$(grep -oE 'applicationId\s+"[^"]+"' android/app/build.gradle 2>/dev/null | grep -oE '"[^"]+"' | tr -d '"')
[ -n "$APP_ID" ] && adb shell dumpsys package "$APP_ID" | grep -E 'versionName|versionCode|lastUpdateTime' | head -3
cat "$PRIMARY/.claude/scratch/device-installed-from" 2>/dev/null || echo "no install marker"
```

| Result | Report |
|---|---|
| `DEVICES_ATTACHED=0` | No phone. No on-device claim is possible this session |
| `DEVICES_ATTACHED` > 1 | Device commands would stop. Name the serials; the user detaches one |
| App not installed | Nothing to test against yet |
| `lastUpdateTime` older than this tree's last change | The phone runs an older or another lane's build |

For the exact owner, `/worktrees slot` (byte comparison).

## Step 5: dependency change

Also diff `android/build.gradle android/app/build.gradle`. If either moved, report that the
resolved Gradle tree must be re-checked for F-Droid cleanliness (non-negotiable 14) before the
next release build.

## Step 6: hazards

| Area | Surface (AGENTS.md non-negotiable) |
|---|---|
| Any logging (`Log.`, `console.`) | 1, 2 |
| `Fetcher`, any network code | 6, 7 |
| `BridgeServer` | 4, 8, 9, 13 |
| `PlaybackService`, `reader` | 9, 11, 13 |
| `segment`, `library`, `Store`, trim | 3, 10, 12 |
| `AndroidManifest.xml`, Gradle files, `package.json` | 14; permissions, `TTS_SERVICE` query, `allowBackup="false"`, FGS type per SPIKE-01 |
| A `spike/rea-*` branch | The code is throwaway. What ships is the answer in `srs.md` "Spikes", plus an ADR if it changes a decision |

## Next action

Item 5: a requirement is met only when observed on the device (R-M14).
