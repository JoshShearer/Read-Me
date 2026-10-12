# /ship override - Read Me

## Step 2: spike branches

A `spike/rea-*` branch's probe code is throwaway. What ships is the recorded answer in `srs.md`
"Spikes" (date, device, build, what was observed, what was not established), plus an ADR in
`docs/adr/` when the answer changes a decision. Stage the doc change, not the probe code, unless
the owner said to keep it; if kept, say why in the PR. No answer recorded → nothing to ship:
stop. The PR Requirement section gives the question, the answer and the ADR.

## Step 4b: the phone

Rules: `AGENTS.md` "Device work: the one-phone slot" (lock format, exit 5 on a locked screen,
no secret on an `adb shell` argv). After taking the slot, `adb devices` must show exactly one
device in state `device`; otherwise release the lock and stop, naming what it showed. Install
with `npm run device:install`, then drive over adb (`am start -n io.loopstring.readme/.MainActivity`,
`input tap` at coordinates from a `uiautomator dump`, never guessed).

| Changed | Do this on the phone |
|---|---|
| `ShareReceiver` / `intake` | Share a real article from a browser and a plain-text snippet from another app. Both land in the list in the right state |
| `Fetcher` / `extract` | Share a long article, a paywalled link, a dead link. `ready`, `extract-poor`, `fetch-failed` with the right reason class; Retry works |
| Trim | Cut paragraphs, reopen, restore one. Nothing lost |
| `PlaybackService` | Play at 2.0x, lock, wait, unlock. Audio continued, notification controls work, position resumed at the right sentence, speed is 2x not 4x |
| `Store` / `library` | `adb shell am force-stop`, reopen. Items and positions survived |
| `BridgeServer` | From Obsidian on the same phone, read a note through the plugin while Read Me is backgrounded (CDP, per the plugin's `AGENTS.md`); then the hostile-input set against the running service |

The scripted `npm run device:*` checks in `AGENTS.md` "Quality gates" supplement this; they do
not replace it. After every run, scan `adb logcat -d -s ReadMe:V ReactNativeJS:V` for item text,
URLs and the token. Any hit is a Step 5 BLOCK even if the feature worked. Obsidian or the plugin
unavailable for a bridge check → that half "did not run", never passing.

## Step 7: commit

- Extra type: `spike`. Scopes mirror `CONTEXT.md`: `intake`, `extract`, `segment`, `library`,
  `reader`, `trim`, `share`, `store`, `fetcher`, `playback`, `bridge`, `build`, `spec`.
- Never stage: `node_modules`, `android/(app/)build/`, `.gradle/`, `*.apk`, `*.aab`, `ui.xml`,
  `*.keystore`, `*.jks`, `.claude/device.lock`, `.claude/last-critique.md`. On a spike branch,
  confirm the probe code is not staged unless the owner kept it.

## Step 10: cross-workspace

Plugin-side bridge work is NRL-130 in the plugin's own Linear workspace. Link it by URL in the
comment if relevant; never post to it from here.
