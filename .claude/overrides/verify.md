# /verify override - Read Me

## Spikes

A spike issue (`SPIKE-0N`, branch `spike/rea-*`) delivers the recorded answer in `srs.md`
"Spikes", not code. Verify the answer cites a measurement taken on a named device; skip Steps
2-3 if the spike branch has no gates. `srs.md` R-M14: a requirement is met only when observed on
the reference device, recorded with date, device and build.

## Step 4: the phone

Rules: `AGENTS.md` "Device work: the one-phone slot". After taking the slot, `adb devices` must
show exactly one device in state `device`; zero, two or `unauthorized` → stop the device half.
Install with `npm run device:install` (absent → `NOT RUN: device:install missing`). Record
`adb shell getprop ro.product.model`, `ro.build.version.release` and `git rev-parse --short HEAD`.

## Step 5: drive it

`$PKG` is `io.loopstring.readme` (`AGENTS.md` Identifiers). Share with
`adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT '<url>'`;
tap at coordinates from `uiautomator dump`; read `adb logcat -d -s <app tag>` only.

A `uiautomator` dump contains article text: read it locally, never paste it (non-negotiable 1).

Baseline checks, where the build has the feature:

| Check | Do | Watch for |
|---|---|---|
| Share intake | The SEND intent, then return to the source app | `fetching`, then `ready`, `extract-poor` or `fetch-failed` (R-M02, R-M10) |
| Playback | Open the item, play at 2.0x | Audible speech, current sentence highlighted (R-M07) |
| Rate once | Change rate mid-read | Applied once. 2.0x that sounds like 4x is non-negotiable 9 broken |
| Screen off | `adb shell input keyevent 26`, wait, wake | Playback continued; position correct (R-M07, R-M11) |
| Offline | Airplane mode on, `adb shell ping -c1 1.1.1.1` unreachable | Offline claims only under this condition (R-M09) |
| Bridge | Obsidian with `local-tts-reader`, CDP via `adb forward tcp:<port> localabstract:webview_devtools_remote_<pid>` | `/health` 200, `/synthesize` returns audio inside Obsidian's WebView; token absent from logcat (R-M12) |

The bridge row's plugin side is NRL-130 in the plugin's workspace: drive it as that repo's
`AGENTS.md` records, file nothing there from here. Restore afterwards: airplane mode off, test
items deleted.

## Clearing app data

The phone is the owner's own, with their library on it. Most `device:*` acceptance scripts begin
with `device_clear_app` (`pm clear`), and `scripts/lib/device.sh` refuses to run it unless
`.claude/device-data-disposable` exists in the primary checkout. **Never create that file
yourself.** If a script stops on it, report the check as not run and name the opt-in, so the owner
can decide.
