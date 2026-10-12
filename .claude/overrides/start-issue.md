# Override: /start-issue - Read Me

Architecture and module paths: `CONTEXT.md`.

## Step 4: spike branches

Title starts `SPIKE-0N:` -> the `Spike` row of `tickets.md` (`spike/rea-{N}-{slug}`). Pre-flight
`git ls-remote` also checks `spike/rea-{N}-*`.

**Spike branches are throwaway.** Say so in the output: the code on a `spike/` branch is not
merged as product code. What merges is the recorded answer in `srs.md` "Spikes" (date, device,
build, measurement), plus an ADR in `docs/adr/` when it changes a decision (SPIKE-06, for one,
can replace non-negotiable 13). Add to Step 9's output:

```
This is a spike. The code here is throwaway; the deliverable is the answer in srs.md "Spikes".
```

## Step 7: area hazards beyond AGENTS.md "Non-negotiables"

Print the matching non-negotiables (numbers in `overrides/create-issue.md` Step 4), plus these
facts that live nowhere else:

| Area | Also know |
|---|---|
| `intake`, `ShareReceiver` | The fetch runs in native code that survives the share activity finishing. Stale `fetching` items become `fetch-failed` at start-up. A failed fetch is visible, never silently deleted (R-M10). |
| `Fetcher` | A test enforces 6: `grep -rnE '\b(fetch\|XMLHttpRequest\|WebSocket)\b' src/`, and no `HttpURLConnection`/`OkHttpClient`/`Socket(` in Kotlin outside `Fetcher`. A chunked body with no `Content-Length` must still hit the 5 MB cap. |
| `extract` | Pure JS on Hermes; whether Readability runs there is SPIKE-02. Fixtures are saved real pages, never a live fetch. |
| `segment` | `Intl.Segmenter` on Hermes is SPIKE-03. |
| `library`, `Store` | Kotlin owns the database (`srs.md` amendment pending). |
| `PlaybackService`, `reader` | Gap target is SPIKE-05's to baseline; never quote one you did not measure. |
| `BridgeServer` | CORS on every response, errors included (R-M12). Plugin side is NRL-130 in the other workspace. |
| Gradle, `package.json` | After any dependency change: `./gradlew :app:dependencies --configuration releaseRuntimeClasspath`, look for `com.google.android.gms`, `firebase`, `crashlytics`. Foreground-service type per SPIKE-01. |

## Step 8: device reproduction

Take the slot per AGENTS.md "Device work: the one-phone slot" (`mkdir .claude/device.lock/` in
the primary, write the `owner` file), `adb devices` shows exactly one device,
`npm run device:install`, drive with `adb shell am start` / `input` / `uiautomator dump`, watch
`adb logcat` filtered by the app's tag. Remove the lock when done and say you held it.
