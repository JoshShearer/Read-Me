# /check-constraints - Read-Me detection table

Rules: `AGENTS.md` "Non-negotiables" (1-14) and "Verification rules" (15-18). Use these checks in
place of derived ones (Step 1). `BASE` as in the shared command. A hit is a candidate; read around
it. Rules 15-17 are covered by the shared universal rows, with the Read-Me probe below for 15.

| # | Grep (added `+` / changed `±` lines) | BLOCK when | N-A when |
|---|---|---|---|
| 1 | `+` `Log\.(v\|d\|i\|w\|e\|wtf)\(\|console\.(log\|info\|warn\|error\|debug)\|println\(\|printStackTrace\|Exception\(\|new Error\(` | Payload is item text, title, shared text, request body, URL path/query, incl. inside an exception message later logged. FP: test `new Error`, fixed string, `uri.host` | no source change |
| 2 | `+` `analytics\|telemetry\|sentry\|bugsnag\|acra\|crashlytics\|firebase\|posthog\|mixpanel\|amplitude\|remote.?config\|update.?check\|fonts\.googleapis\|cdn` (`-i`) | Any hit not a comment saying there is none | |
| 3 | `±` `allowBackup\|getExternal\|Environment\.getExternalStorage\|MediaStore\|WRITE_EXTERNAL_STORAGE\|fullBackupContent\|dataExtractionRules` | `allowBackup` not `"false"`, external-storage write, item delete leaving text (read the delete path if `Store` changed) | |
| 4 | `+` `token\|Authorization\|Bearer` (`-i`) | Token on a log, format, toast, URL (`?token=`) or exception line. Settings + Copy is sanctioned | |
| 5 | `+` `api[_-]?key\|apiKey\|elevenlabs\|azure\|polly\|openai\|googleapis\|deepgram\|speechify\|isNetworkConnectionRequired` (`-i`) | A cloud endpoint, credential or account; a network voice selectable or a fallback | |
| 6 | `+` in `*.ts *.tsx *.js *.jsx`: `fetch\(\|XMLHttpRequest\|WebSocket`; in `*.kt`: `Socket\(\|ServerSocket\(\|openConnection\|HttpURLConnection\|OkHttpClient\|URL\(` | JS hit outside the network-guard test or a fixture; Kotlin hit outside `Fetcher` (except `ServerSocket` in `BridgeServer`, parse-only `URL(`); a fetch reachable from anything but share/Retry; guard test narrowed or deleted | |
| 7 | `±` `NO_COOKIES\|CookieJar\|cache\(\|followRedirects\|followSslRedirects\|callTimeout\|MAX_REDIRECTS\|MAX_BYTES\|5 \* 1024` | `Fetcher` lost any of: `NO_COOKIES`, no cache, `followRedirects(false)` + manual loop <= 5 refusing non-http(s), 20 s call timeout, 5 MB cap while streaming | `Fetcher` unchanged |
| 8 | `±` `getLoopbackAddress\|0\.0\.0\.0\|127\.0\.0\.1\|ServerSocket\|soTimeout\|setSoTimeout\|Content-Length\|Throwable\|catch \(e: Exception\)\|Access-Control\|getQueryParameter` | Read `BridgeServer` whole: explicit `127.0.0.1`; token before body; 16 KiB header / 64 KiB body caps with `Content-Length` validated first; per-socket timeout; off the accept thread; catch `Throwable`; text from POST body; CORS on every response. Run the hostile set if the bridge can run, else say "by reading only" | `BridgeServer` unchanged |
| 9 | `±` `setSpeechRate\|PlaybackParams\|setPlaybackSpeed\|playbackRate\|\bspeed\b\|\brate\b\|X-Rate` | A second speed stage, bridge resampling, text telling the plugin to send its user rate, a rate test's expected numbers changed | |
| 10 | `+` `sentence_?index\|sentenceIdx\|utterance_?index\|currentSentence\|position` (`-i`) | A persisted field/column/value addressing a sentence or utterance by index (transient is fine) | |
| 11 | `+` in `*.ts *.tsx`: `speak\|enqueue\|next\|advance\|savePosition\|onDone\|setInterval\|setTimeout` | JS enqueues, advances or saves a position on a playback event | |
| 12 | `+` `DELETE FROM paragraphs\|UPDATE paragraphs\|cuts\|trim` (`-i`) | Trim deletes or overwrites paragraph text | |
| 13 | `±` in `*.kt`: `TextToSpeech\(\|object .*Tts\|lateinit var tts\|companion object` | One instance shared by `PlaybackService` and `BridgeServer` (ADR 0004) | |
| 14 | changed files matching `build\.gradle\|settings\.gradle\|gradle\.properties\|libs\.versions\.toml\|package\.json\|AndroidManifest\.xml` | Resolved tree hit (command below), non-OSI license (read it; ADR 0002 exceptions), binary without source, manifest lost `TTS_SERVICE` `<queries>`, gained an unneeded permission, `allowBackup` true, or a foreground service without `foregroundServiceType`. No `android/gradlew`: UNKNOWN | nothing matched |
| 18 | `+` `npm run \|npx \|gradlew\|sdkmanager\|adb \|\.sh\b` | Depends on a script/SDK component absent on this machine. Check with `node -e "console.log(Object.keys(require('./package.json').scripts\|\|{}).join(' '))"`, `ls android/gradlew ~/Android/Sdk/build-tools ~/Android/Sdk/platforms`, `which adb` | |

The `\|` above is table escaping: in the shell it is a plain `|` inside `grep -E`.

Rule 14, resolved tree:

```bash
ls android/gradlew && (cd android && ./gradlew :app:dependencies --configuration releaseRuntimeClasspath) \
  | grep -niE 'com\.google\.android\.gms|firebase|crashlytics|play-services|com\.google\.android\.play'
```

Rule 15 probe (is the build on the phone newer than the change). An install older than the last
commit, or no device attached, is **UNKNOWN**, never BLOCK. A not-verified change may still ship
under the not-verified wording (contract rule 2):

```bash
adb devices
adb shell dumpsys package io.loopstring.readme | grep -E 'versionName|lastUpdateTime'
git log -1 --format=%ci
```

Rule 17: the plugin repo's `AGENTS.md` is a valid citation.
