# Project facts - Read-Me

Read by every shared `claude-workflow` command (see `.claude/workflow/contract.md`).
**The `##` headings are an interface.** Keep them as written. Project-specific steps that aren't
facts live in `.claude/overrides/<command>.md`.

## Identity

| Field | Value |
|---|---|
| Name | Read-Me |
| One line | Android reader (bare React Native + one Kotlin module) that reads shared articles aloud with on-device TTS, and serves a loopback TTS bridge for the `local-tts-reader` Obsidian plugin |
| Default branch | `main` |
| Agent instructions | `AGENTS.md` (`CLAUDE.md` is a symlink to it) |
| Remote | `origin` (`JoshShearer/Read-Me`, public) |

## Gates

The full list is the "Quality gates" block in `AGENTS.md`. These are the gates that run off-device.

| Gate | Command | Required when the diff touches |
|---|---|---|
| typecheck | `npm run typecheck` | any TS (`src/`, TS tests) |
| lint | `npm run lint` | any TS |
| test | `npm test` | any TS |
| test-scripts | `npm run test:scripts` | `scripts/` |
| licenses | `node scripts/check-licenses.mjs` | `package.json` dependencies, lockfile |
| kotlin-test | `(cd android && ./gradlew testDebugUnitTest)` | anything under `android/` |
| release-build | `npm run build:release` (`READ_ME_ABIS=arm64-v8a` for the phone only) | `android/` Kotlin, any Gradle file, `AndroidManifest.xml`, `package.json` dependencies |
| fdroid-scan | `scripts/fdroid-scan.sh` | any Gradle file, `AndroidManifest.xml`, `package.json` dependencies |

Setup in a fresh checkout: `npm ci`, then copy `android/local.properties` from the primary checkout
if it's missing. Without it the Gradle gates fail for reasons unrelated to the change. A fresh
worktree has no `node_modules` and no Gradle cache.

CI: `.github/workflows/ci.yml`, with jobs `js` and `android`. It runs on push to `main` and on
pull_request. Read a check result once. Never wait on it. Branch protection is out of scope.

## Verification

| Field | Value |
|---|---|
| Target | The one attached Android phone (the reference device in `srs.md`), over adb. Bridge checks also need Obsidian with `local-tts-reader` on the same phone |
| How | Take the slot, check that `adb devices` shows exactly one device, run `npm run device:install`, then drive the app as a user would. The scripted `device:*` checks are listed in `AGENTS.md` "Quality gates". The procedure is in `overrides/verify.md` |
| Verified wording | `VERIFIED ON DEVICE` |
| Not-verified wording | `NOT VERIFIED ON DEVICE` (`/verify`'s verdict: `GATES GREEN, NOT VERIFIED`) |
| Exclusive resource | `.claude/device.lock/` in the primary checkout. The owner file format is in `AGENTS.md` "Device work: the one-phone slot" |

## Risk areas

Highest risk first. The numbers are non-negotiables in `AGENTS.md`.

- `item text in logs` (+2) - a log line or an exception message carries a sentence, title, shared
  text, URL path or query, or the bridge token (1, 4). Match: `Log\.(v|d|i|w|e|wtf)\(`,
  `console\.`, `println\(`, `printStackTrace`, `Exception\(`, `new Error\(`
- `network surface` (+2) - a fetch outside `Fetcher`, or one reachable from anything other than a
  share or a Retry. Or `Fetcher`'s limits get loosened (5, 6, 7). Match: `fetch\(`,
  `XMLHttpRequest`, `WebSocket` in `*.{ts,tsx,js,jsx}`; `Socket\(`, `ServerSocket\(`,
  `openConnection`, `OkHttpClient`, `URL\(` in `*.kt`; any change to `Fetcher`
- `BridgeServer` (+2) - binds to `::1` or all interfaces, reads the body before the token, sizes a
  buffer from `Content-Length`, lets a silent client stall `/health`, sends an error response
  without CORS, or leaves a WAV behind (8). Match: `BridgeServer` changed (bind, token, caps,
  timeouts, CORS, cache cleanup, routes)
- `rate handling` (+2) - rate applied twice, so 2x plays at 4x (9). Match: `setSpeechRate`,
  `rate`, `speed`, `playbackRate`, `X-Rate` on an added line
- `playback ownership` (+2) - JS drives the queue, so playback stops when the screen goes off. Or a
  late `onDone` saves a position onto the next item (11). Match: `PlaybackService`, its queue,
  `UtteranceProgressListener`, `MediaSession`, a JS call that starts, advances or stops playback
- `persisted positions or items` (+2) - a stored sentence index resumes at the wrong sentence after
  a runtime update. Or a trim destroys paragraphs, or a delete leaves text behind (3, 10, 12).
  Match: `Store`, the `library` facade, the schema, a migration, anything that writes a position
  or a cut
- `manifest or dependency` (+2) - a transitive Play Services dependency fails the F-Droid scan. Or
  a lost `TTS_SERVICE` `<queries>` entry breaks TTS init on API 30+ (14). Match:
  `AndroidManifest.xml`, `build.gradle*`, `settings.gradle*`, `gradle.properties`,
  `libs.versions.toml`, `package.json` dependencies and devDependencies
- `text pipeline` (+1) - a break corrupts the reading instead of crashing. Match: `intake`,
  `extract`, `segment`, trim math
- `TextToSpeech construction` (+1) - playback and the bridge share one instance, or an engine is
  never shut down (13). Match: `TextToSpeech(`, `.shutdown()`, engine selection

## Non-negotiables

`AGENTS.md`, sections "Non-negotiables" (rules 1-14) **and** "Verification rules" (rules 15-18).
A BLOCK in either stops `/ship`.

## Spec

| Field | Value |
|---|---|
| File | `srs.md` (spikes are under "Spikes (before implementation)") |
| Requirement ID pattern | `R-[MSC]\d{2}`, `SPIKE-\d{2}` |
| Baseline | `AGENTS.md` "Known state", subsection "Spec-check baseline" (not recorded yet) |
| Deviation record | An ADR in `docs/adr/` (numbered after the highest existing one), plus an `srs.md` amendment that points to it, in the same commit |

## Push policy

`allowed` - this repo's commands may push, open PRs and post Linear comments without asking.
docs-only commits to the default branch: allowed (`/update-docs` and `/finish` doc fixes).

## Worktrees

| Field | Value |
|---|---|
| Location | sibling of the primary checkout (`<parent>/`) |
| Name pattern | `Read-Me-rea-{N}` (multi-issue: `Read-Me-rea-12-13`). `Read-Me-run-*` lanes belong to `/run-tickets` |
| Setup | `npm ci`, then copy `android/local.properties` from the primary (see `overrides/worktrees.md`) |

## Docs to keep in sync

- `AGENTS.md` - the "Quality gates" block, "Known state" (and the spec-check baseline once it is
  recorded)
- `srs.md` - spike answers under "Spikes", deviations and their amendments
- `CONTEXT.md` - "Source layout" (it also records the applicationId and the log tags `/verify`
  and `/finish` read), "Layers", "Known structural gaps"
- `docs/adr/` - new decision records only. Don't diff these. Write one when a deviation needs it
- `.claude/COMMANDS.md`

## Autonomous runs

`/run-tickets` facts. The Read-Me-only steps (the phone, F-Droid, spikes) are in
`overrides/run-tickets.md`.

| Field | Value |
|---|---|
| Gate-defining files | `package.json`, `package-lock.json`, `tsconfig.json`, `.eslintrc.js`, `.eslintignore`, `.prettierrc.js`, `jest.config.js`, `babel.config.js`, `scripts/tests/`, `scripts/lib/`, `scripts/check-licenses.mjs`, `scripts/license-expr.mjs`, `scripts/make-notices.mjs`, `scripts/make-devcheck-fixtures.mjs`, `android/build.gradle`, `android/settings.gradle`, `android/gradle.properties`, `android/gradle/`, `android/app/build.gradle`, `android/app/proguard-rules.pro`, `android/app/src/test/` |
| Pipeline files | `scripts/lib/device.sh` (the lane's data-clear guard) |
| Owner-only files | `release/signing-cert.sha256` (the signer every release is checked against) |
| Verify-only gates | `release-build`, `fdroid-scan` |
| Target from a lane | Lock `.claude/device.lock/` (the Exclusive resource). Opt-in file `.claude/device-data-disposable`: only the owner creates it, and only with it may a lane run the `device:*` scripts that clear app data |
| Target guard files | `scripts/lib/device.sh` |
| Forbidden unattended actions | `git tag`, `gh release`, `npm run release:keystore`, `npm run release:apk`, `npm run fdroid:build`, `npm run repro`, any edit to the fdroiddata merge request, `npm start`, `npm run android`, `npm run device:screenshots`, `AIRPLANE=1`, `adb uninstall`, a data-wiping install |
