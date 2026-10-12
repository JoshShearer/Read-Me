# /test-issue override - Read Me

## Step 2: workflows

`.github/workflows/ci.yml` triggers on `push` to `main` and on `pull_request`, so a feature
branch normally gets **one** run per job (the PR run), not two. Jobs: `js`, `android`. There is
no tag-only release workflow yet.

## Step 4: step-to-gate map

| Failing step (`ci.yml`) | Run locally |
|---|---|
| `npm ci` | `npm ci` |
| `npm run typecheck` / `lint` / `test` / `test:scripts` | the same script, then the single failing file |
| `node scripts/check-licenses.mjs` | the same |
| Kotlin unit tests and release build | `(cd android && ./gradlew testDebugUnitTest assembleRelease)` |
| Licenses asset matches the dependencies | `node scripts/make-notices.mjs --check` |
| No non-free dependencies in the resolved tree | `scripts/fdroid-scan.sh` (or the `:app:dependencies` grep in `ci.yml`) |

Local-versus-CI differences to check first: CI is `ubuntu-24.04`, Node 22, Temurin JDK 21, the
SDK packages `ci.yml` installs (compare `sdkmanager --list_installed`), `--no-daemon` and a clean
Gradle cache. Anything needing `adb` cannot run in CI.

## Step 5 / 7: cross-workspace

A failure in plugin-side bridge code belongs to NRL-130 in the plugin's workspace. Say so in the
report; never file or comment on it through `linear-rea`.
