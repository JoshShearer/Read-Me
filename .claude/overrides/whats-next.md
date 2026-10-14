# /whats-next override - Read Me

Rules: `AGENTS.md` "Verification rules" (15, 17), R-M14 in `srs.md`.

## Section 2: spikes

For a spike: the answer, and whether it is written into `srs.md` "Spikes" yet. Until it is, the
spike is not done, whatever the branch holds.

## Section 5: blockers

Also: Obsidian not on the phone; plugin-side work tracked as NRL-130 in the plugin's workspace
(link by URL; the workspaces cannot link natively).

## Section 6: extra measurement rows

| Capture | How |
|---|---|
| Release build | real APK size (`ls -l`) and the resolved-dependency F-Droid check, verbatim |
| Manifest | permissions and `<queries>` from the merged manifest, verbatim |
| Synthesis | RTF = compute s / audio s, with input length, rate, engine, and whether offline (how checked) |
| Inter-utterance gap | Player end(n) to start(n+1) per R-M07 (ADR 0012): p95, max, sample count, run duration |
| Bridge | exact `curl` status codes for the hostile-input set; pid before and after |
| Device and build | device, Android version, TTS engine package, `versionName`/`versionCode`, commit |

Numbers already in the plugin repo's `AGENTS.md` ("Android playback throughput, and the native-TTS
bridge") are cited from there, not presented as this session's.

## Section 7: extra unverified checks

- Whether the bridge was exercised from Obsidian on the phone (CDP via
  `adb forward ... localabstract:webview_devtools_remote_<pid>`) or only by `curl` over
  `adb forward`. Different evidence.
- Whether a run was genuinely offline, and how that was checked.
- Whether logcat was checked for item text during the run (non-negotiable 1).

## When to use

Also: ending a spike whose answer is not yet in `srs.md`.
