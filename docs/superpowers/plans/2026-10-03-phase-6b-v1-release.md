# Phase 6b: v1 Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Read Me 1.0.0 releasable. That means four things:
- complete license notices
- a signing path whose secrets stay out of the repo
- a build that is reproducible and accepted by F-Droid
- R-M14's on-device acceptance runs recorded on the release content

The owner creates the keystore and publishes.

**Architecture:**
- **Day-to-day builds** stay debug-signed, so the device scripts and CI keep working.
- **The release build** is signed only when `npm run release:apk` asks for it. The script reads the keystore from `~/.gradle/gradle.properties` or the environment, and checks the signer against a committed public certificate fingerprint.
- **Same content as tested.** `apksigcopier compare` proves the signed APK has the same content as the debug-signed build the acceptance runs used.
- **F-Droid.** The F-Droid recipe builds `hermesc` from the Hermes source at the tag that matches the prebuilt one. A repro script builds twice from two clean clones in different paths and compares the results.

**Tech Stack:**
- Bash, Node 24 (ESM scripts, `node:test`), Gradle 9.4.1 / AGP, React Native 0.87.1
- fdroidserver 2.4.5 and apksigcopier in `.venv-fdroid`
- `apksigner` from build-tools 37.0.0, `keytool` (JDK 21)

**Spec:** `srs.md` R-M13 (Distribution) and R-M14 (Verification). Also SPIKE-04's answer, ADR 0002, and REA-26's scope (Linear). REA-21 lists the R-M13 notice items.

## Global Constraints

- R-M13: "v1 ships as a signed APK on GitHub Releases, then on F-Droid."
- R-M13: "builds from a clean checkout with documented commands".
- R-M13: "Third-party license notices MUST ship in the app (Settings > Licenses)."
- R-M13: `minSdk` 24, `targetSdk` 36. Both are already set in `android/build.gradle`; do not change them.
- R-M14: "A requirement is met only when observed on a real device, and the observation is recorded with the date, device and build."
- Owner decision: signing secrets never enter the repo, a log, or a command line. `keytool` and Gradle read them interactively, from `~/.gradle/gradle.properties` or from env.
- AGENTS.md: never put a secret on an `adb shell` command line.
- Owner decision: everything is prepared here. The owner makes the repo public, publishes the GitHub Release and opens the F-Droid merge request.
- Owner preference (memory `gap-run-length`): the 10-minute gap run happens once, here, as R-M14 run 2. Device runs are ordered so the screen-off ones come last.
- AGENTS.md 14: F-Droid-clean. Every dependency is OSI, except ADR 0002 data packages.
- No em-dashes in any file, commit or doc.

## Review Focus

1. **A release APK signed with the debug key gets published.** `release:apk` must refuse any APK whose signer fingerprint is not the committed release fingerprint. Test: `release-lib.test.sh`, "a debug-signed APK is refused" (Task 2).
2. **A password reaches argv or a log.** No script passes a password to `keytool`, `apksigner` or Gradle on the command line. Test: `release-lib.test.sh` greps the scripts for `-storepass`, `-keypass` and `--ks-pass pass:` (Task 2).
3. **The notices asset differs on F-Droid's machine** (a different `GRADLE_USER_HOME`, or no network for `npx`). Then `--check` fails or the screen drifts. Test: `notices.test.mjs`, "the Gradle cache honours GRADLE_USER_HOME" (Task 1). CI runs `--check`.
4. **A build path leaks into a native library or the bundle**, so F-Droid's build (in another directory) never matches ours. Test: `scripts/repro-check.sh` builds in two different temp paths and compares (Task 4).
5. **A notice section the screen does not render.** A `native` entry with text must show its text when tapped. Test: `model.test.ts`, "parseNotices includes native entries with their text" (Task 1).

---

## File map

| File | Responsibility |
|---|---|
| `scripts/make-notices.mjs` | Adds a `native` section and license texts for Maven and text-less npm entries; honours `GRADLE_USER_HOME`; runs a pinned local license-checker |
| `scripts/notices/native.json`, `scripts/notices/texts/*.txt` | Hand-checked list of the native components compiled into the APK, and their license texts |
| `scripts/notices/spdx/*.txt` | Canonical license texts, used where an artifact ships none |
| `src/ui/model.ts` | `parseNotices` reads the `native` section |
| `android/app/build.gradle` | Release signing config, used only with `-PreadmeSign=release` |
| `scripts/lib/release.sh` | `cert_sha256 <apk>`, `require_release_cert <apk>` |
| `scripts/release-keystore.sh` | The owner's one-time `keytool` run (interactive) and the fingerprint file |
| `scripts/release-apk.sh` (`npm run release:apk`) | Signed build, cert check, `apksigcopier compare`, `SHA256SUMS` |
| `release/signing-cert.sha256` | The public certificate fingerprint (committed after the owner creates the key) |
| `scripts/build-hermesc.sh` | Builds `hermesc` from Hermes at the pinned tag |
| `fdroid/io.loopstring.readme.yml` | The F-Droid recipe for the merge request |
| `scripts/fdroid-scan.sh` | Scans the tree after `npm ci`, with the recipe's deletions |
| `scripts/repro-check.sh` (`npm run repro`) | Two clean-clone builds in different paths, then compares them |
| `scripts/device-accept-share.sh` (`npm run device:accept-share`) | R-M14 run 1 |
| `scripts/device-gap.sh` | `AIRPLANE=1` option (R-M14 run 2) |
| `LICENSE`, `package.json`, `android/app/build.gradle` | MIT license file, version 1.0.0 |
| `fastlane/metadata/android/en-US/changelogs/1.txt` | Changelog |
| `docs/release.md` | The owner's runbook |
| `AGENTS.md`, `.github/workflows/ci.yml` | Gate lines, Known state, CI notices check |

---

### Task 1: Complete the license notices (R-M13, REA-21 items)

**Files:**
- Modify: `scripts/make-notices.mjs`, `src/ui/model.ts`, `package.json` (devDependency), `.github/workflows/ci.yml`
- Create: `scripts/notices/native.json`, `scripts/notices/texts/` and `scripts/notices/spdx/`
- Test: `scripts/tests/notices.test.mjs`, `src/ui/__tests__/model.test.ts` (or wherever the existing `parseNotices` test lives: `grep -rln parseNotices src`)
- Regenerate: `android/app/src/main/assets/notices.json`

**Interfaces:**
- Produces:
  - `export function gradleCache(env = process.env): string`, which returns `$GRADLE_USER_HOME/caches/modules-2/files-2.1`, or `~/.gradle/...` when the variable is unset.
  - `export function spdxFor(licenseName: string): string | null`, which maps POM and npm license names to an SPDX id with a text in `scripts/notices/spdx/`.
  - `notices.json` gains `native: [{name, version, license, text}]`, and Maven entries gain `text`.
  - `parseNotices` returns npm, then android, then native entries.

- [ ] **Step 1: Pin down what is actually compiled in.**
  1. Run `npm run build:release`.
  2. Run `unzip -l android/app/build/outputs/apk/release/app-release.apk | grep '\.so$'`.
     - Expected: the 11 arm64 libraries seen 2026-10-03, among them `libreactnative.so`, `libc++_shared.so`, `libhermesvm.so`, `libfbjni.so` and `libimagepipeline.so`.
  3. For each library not already covered by a Maven entry, find its source. `libreactnative.so` statically links folly 2024.11.18.00, glog 0.3.5, double-conversion 1.1.6, fast_float 8.0.0, fmt 12.1.0, boost 1_83_0 and gflags 2.2.0 (`node_modules/react-native/gradle/libs.versions.toml`).
  4. Check each with `strings -a lib/arm64-v8a/libreactnative.so | grep -ciE 'folly|google::|double_conversion|fast_float|fmt::|boost'`. List only the ones that are present.
  5. `libc++_shared.so` comes from NDK 27.1.12297006 under Apache-2.0 WITH LLVM-exception.
  6. Record the outcome in `scripts/notices/native.json`:

```json
[
  {"name": "folly", "version": "2024.11.18.00", "license": "Apache-2.0", "text": "texts/folly-LICENSE.txt"},
  {"name": "glog", "version": "0.3.5", "license": "BSD-3-Clause", "text": "texts/glog-COPYING.txt"},
  {"name": "double-conversion", "version": "1.1.6", "license": "BSD-3-Clause", "text": "texts/double-conversion-LICENSE.txt"},
  {"name": "fast_float", "version": "8.0.0", "license": "Apache-2.0 OR MIT OR BSL-1.0", "text": "texts/fast_float-LICENSE-MIT.txt"},
  {"name": "fmt", "version": "12.1.0", "license": "MIT", "text": "texts/fmt-LICENSE.txt"},
  {"name": "boost", "version": "1.83.0", "license": "BSL-1.0", "text": "texts/boost-LICENSE_1_0.txt"},
  {"name": "gflags", "version": "2.2.0", "license": "BSD-3-Clause", "text": "texts/gflags-COPYING.txt"},
  {"name": "LLVM libc++ (NDK 27.1.12297006)", "version": "27.1.12297006", "license": "Apache-2.0 WITH LLVM-exception", "text": "texts/libcxx-LICENSE.TXT"}
]
```

  Drop any entry whose strings check finds nothing; ledger the drop. Fetch each text from upstream at the pinned tag: for example `curl -fsSL https://raw.githubusercontent.com/facebook/folly/v2024.11.18.00/LICENSE`. For libc++, use `$ANDROID_HOME/ndk/27.1.12297006/` if it holds a NOTICE, else llvm-project's `libcxx/LICENSE.TXT`. Commit each text with its source URL as its first line.

- [ ] **Step 2: Write the failing tests** in `scripts/tests/notices.test.mjs`:

```js
import {gradleCache, spdxFor, nativeNotices} from '../make-notices.mjs';

test('the Gradle cache honours GRADLE_USER_HOME', () => {
  assert.equal(gradleCache({GRADLE_USER_HOME: '/x/gh'}), '/x/gh/caches/modules-2/files-2.1');
  assert.match(gradleCache({}), /\.gradle\/caches\/modules-2\/files-2\.1$/);
});

test('POM and npm license names map to a shipped SPDX text', () => {
  assert.equal(spdxFor('The Apache Software License, Version 2.0'), 'Apache-2.0');
  assert.equal(spdxFor('Apache-2.0'), 'Apache-2.0');
  assert.equal(spdxFor('MIT'), 'MIT');
  assert.equal(spdxFor('BSD-3-Clause'), 'BSD-3-Clause');
  assert.equal(spdxFor('Some Custom License'), null);
});

test('every native notice carries its license text', () => {
  for (const n of nativeNotices()) {
    assert.ok(n.text && n.text.length > 200, `${n.name} has no text`);
  }
});
```

  Then, in the `parseNotices` test file, add:

```ts
test('parseNotices includes native entries with their text', () => {
  const j = JSON.stringify({npm: [], android: [], native: [{name: 'folly', version: '1', license: 'Apache-2.0', text: 'T'}]});
  expect(parseNotices(j)).toEqual([{name: 'folly', version: '1', license: 'Apache-2.0', text: 'T'}]);
});
```

- [ ] **Step 3: Run them.** `npm run test:scripts` and `npx jest model`.
  - Expected: FAIL. `gradleCache`, `spdxFor` and `nativeNotices` are not exported, and `parseNotices` drops `native`.

- [ ] **Step 4: Implement.** In `make-notices.mjs`:
  - **The cache.** Replace `GRADLE_CACHE` with `gradleCache()`, built as `join(env.GRADLE_USER_HOME ?? join(homedir(), '.gradle'), 'caches/modules-2/files-2.1')`.
  - **`spdxFor`.** A table of the license names actually in today's `notices.json`: run `node -e "const d=require('./android/app/src/main/assets/notices.json');console.log([...new Set([...d.npm,...d.android].map(n=>n.license))].join('\n'))"` and map each OSI name to its SPDX id. Ship `scripts/notices/spdx/<id>.txt` for each id, from `https://raw.githubusercontent.com/spdx/license-list-data/main/text/<id>.txt`.
  - **Maven entries.** They get `text` from the SPDX file. An expression such as `A OR B` uses the first id that has a file.
  - **npm entries without `text`.** They get the SPDX text with a first line: `(Standard <id> text; the package ships no license file.)`.
  - **`nativeNotices()`.** It reads `native.json` and inlines each text file.
  - **license-checker.** Add `license-checker-rseidelsohn` `5.0.1` to `devDependencies`, exactly pinned. Call `node_modules/.bin/license-checker-rseidelsohn` instead of `npx -y`, because F-Droid builds offline.
  - **The output** gains `native: nativeNotices()`.

  In `model.ts`, `parseNotices` appends `d.native ?? []` after android.

  Add a CI step to the `android` job, after the release build: `- run: node scripts/make-notices.mjs --check`. The Gradle cache is populated by then.

- [ ] **Step 5: Run.** `npm run notices && npm run test:scripts && npm test && npm run typecheck && npm run lint && node scripts/check-licenses.mjs`.
  - Expected: all pass, and `notices` prints `wrote N npm, M Android and K native notices` (change the message to name all three).
  - Check that `license-checker-rseidelsohn` is OSI licensed (BSD-3-Clause) and passes `check-licenses.mjs`. Dev dependencies are outside the production scan, so confirm with `npm view license-checker-rseidelsohn@5.0.1 license`.

- [ ] **Step 6: Commit.** `feat(build): ship native component notices and license texts (R-M13)`

### Task 2: Release signing without secrets in the repo

**Files:**
- Modify: `android/app/build.gradle` (signingConfigs and the release buildType), `package.json` (`release:apk`, `release:keystore`, `test:scripts`), `.gitignore` (`release/*.apk`, `release/SHA256SUMS`, `*.jks`, `*.keystore` except `android/app/debug.keystore`)
- Create: `scripts/lib/release.sh`, `scripts/release-keystore.sh`, `scripts/release-apk.sh`, `scripts/tests/release-lib.test.sh`

**Interfaces:**
- Produces:
  - Gradle: `-PreadmeSign=release` selects `signingConfigs.release`. That config reads `READ_ME_STORE_FILE`, `READ_ME_STORE_PASSWORD`, `READ_ME_KEY_ALIAS` and `READ_ME_KEY_PASSWORD` from Gradle properties (`~/.gradle/gradle.properties`, or `ORG_GRADLE_PROJECT_*` env). It fails the build with a named message if any is missing. Without the flag, release builds stay debug-signed (unchanged).
  - `scripts/lib/release.sh`:
    - `cert_sha256 <apk>` prints the lowercase hex SHA-256 of the signer certificate (`apksigner verify --print-certs`, the `Signer #1 certificate SHA-256 digest:` line).
    - `require_release_cert <apk>` exits 1 and names both fingerprints unless that digest equals `release/signing-cert.sha256`.

- [ ] **Step 1: Write the failing test** `scripts/tests/release-lib.test.sh`:

```bash
#!/usr/bin/env bash
# release.sh: a debug-signed APK is refused; no script passes a password on a command line.
set -uo pipefail
cd "$(dirname "$0")/../.."
source scripts/lib/release.sh
fail=0
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
BT="${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools/37.0.0"
# A minimal signed APK: an empty manifest-less zip signs fine with an explicit min SDK.
printf 'x' > "$T/a"; (cd "$T" && zip -q u.zip a)
# The debug keystore's passwords are public ("android"), so argv is acceptable here only.
# The grep below skips the debug config's public 'android' password the same way.
"$BT/apksigner" sign --ks android/app/debug.keystore --ks-pass pass:android --min-sdk-version 24 --out "$T/d.apk" "$T/u.zip" || { echo "FAIL: could not sign the fixture"; exit 1; }
want=$(keytool -list -v -keystore android/app/debug.keystore -storepass android 2>/dev/null | sed -n 's/.*SHA256: //p' | head -1 | tr -d ':' | tr 'A-F' 'a-f')
[ "$(cert_sha256 "$T/d.apk")" = "$want" ] && echo "ok: cert_sha256 reads the signer" || { echo "FAIL: cert_sha256"; fail=1; }
echo deadbeef > "$T/fp"
RELEASE_CERT_FILE="$T/fp" require_release_cert "$T/d.apk" >/dev/null 2>&1 && { echo "FAIL: a debug-signed APK was accepted"; fail=1; } || echo "ok: a debug-signed APK is refused"
echo "$want" > "$T/fp"
RELEASE_CERT_FILE="$T/fp" require_release_cert "$T/d.apk" >/dev/null && echo "ok: the matching cert is accepted" || { echo "FAIL: matching cert refused"; fail=1; }
if grep -nE -- '-storepass|-keypass|--ks-pass|--key-pass|storePassword +["'"'"']' scripts/release-*.sh scripts/lib/release.sh android/app/build.gradle | grep -v "'android'"; then
  echo "FAIL: a password on a command line or in the build file"; fail=1
else echo "ok: no password on a command line"; fi
exit $fail
```

  Add `&& bash scripts/tests/release-lib.test.sh` to `test:scripts`.

- [ ] **Step 2: Run** `bash scripts/tests/release-lib.test.sh`.
  - Expected: FAIL, because `scripts/lib/release.sh` does not exist.
  - If `apksigner` will not sign the manifest-less zip, use `unzip`/`zip` on the debug-signed `app-release.apk` from Task 1 as the fixture instead, and ledger it.

- [ ] **Step 3: Implement.**

  `scripts/lib/release.sh`:

```bash
# Release signing checks (REA-26). Public data only: certificate fingerprints, never passwords.
BT="${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools/37.0.0"
cert_sha256() {
  "$BT/apksigner" verify --print-certs "$1" 2>/dev/null \
    | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1
}
require_release_cert() {
  local want got
  want=$(tr -d ' \n' < "${RELEASE_CERT_FILE:-release/signing-cert.sha256}" 2>/dev/null)
  got=$(cert_sha256 "$1")
  if [ -z "$want" ] || [ "$got" != "$want" ]; then
    echo "refused: $1 is signed by ${got:-nothing}, not the release key ${want:-(no release/signing-cert.sha256)}" >&2
    return 1
  fi
}
```

  `android/app/build.gradle`: inside `signingConfigs`, after `debug`:

```groovy
        release {
            // REA-26: the owner's key, read from ~/.gradle/gradle.properties or ORG_GRADLE_PROJECT_*
            // env; nothing secret is in this repo. Used only with -PreadmeSign=release.
            if (findProperty('readmeSign') == 'release') {
                def need = ['READ_ME_STORE_FILE', 'READ_ME_STORE_PASSWORD', 'READ_ME_KEY_ALIAS', 'READ_ME_KEY_PASSWORD']
                def missing = need.findAll { !findProperty(it) }
                if (missing) throw new GradleException("release signing needs Gradle properties: ${missing.join(', ')}")
                storeFile file(findProperty('READ_ME_STORE_FILE'))
                storePassword findProperty('READ_ME_STORE_PASSWORD')
                keyAlias findProperty('READ_ME_KEY_ALIAS')
                keyPassword findProperty('READ_ME_KEY_PASSWORD')
            }
        }
```

  Then, in `buildTypes.release`: `signingConfig findProperty('readmeSign') == 'release' ? signingConfigs.release : signingConfigs.debug`. Replace the RN template's "Caution!" comment with one line on why debug stays the default (device scripts and CI).

  `scripts/release-keystore.sh` (the owner runs it once; nothing secret on argv):

```bash
#!/usr/bin/env bash
# One-time: create the release key. keytool prompts for both passwords; they never reach argv.
set -euo pipefail
cd "$(dirname "$0")/.."
KS=${1:-$HOME/.android-keys/read-me-release.jks}
[ -e "$KS" ] && { echo "refused: $KS exists; never overwrite a release key"; exit 1; }
mkdir -p "$(dirname "$KS")"; chmod 700 "$(dirname "$KS")"
keytool -genkeypair -v -keystore "$KS" -alias read-me -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Read Me, O=Loopstring"
chmod 600 "$KS"
keytool -list -v -keystore "$KS" -alias read-me | sed -n 's/.*SHA256: //p' | head -1 | tr -d ':' | tr 'A-F' 'a-f' > release/signing-cert.sha256
echo "fingerprint written to release/signing-cert.sha256 (public; commit it)"
echo "Add to ~/.gradle/gradle.properties (chmod 600), with your passwords:"
echo "  READ_ME_STORE_FILE=$KS"
echo "  READ_ME_KEY_ALIAS=read-me"
echo "  READ_ME_STORE_PASSWORD=..."
echo "  READ_ME_KEY_PASSWORD=..."
echo "Back up $KS and both passwords offline: losing them ends updates for every installed copy."
```

  The second `keytool -list` prompts for the store password, again interactively.

  `scripts/release-apk.sh` (`npm run release:apk`):

```bash
#!/usr/bin/env bash
# REA-26: the publishable APK. Builds the release signed with the owner's key, checks the signer,
# proves the content equals the debug-signed build the acceptance runs used, writes SHA256SUMS.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/release.sh
[ -z "$(git status --porcelain -- . ':(exclude).claude')" ] || { echo "refused: the tree is dirty"; exit 1; }
TESTED=${1:?usage: release-apk.sh <the debug-signed APK the acceptance runs installed>}
VER=$(node -p "require('./package.json').version")
node scripts/make-notices.mjs --check
( cd android && ./gradlew --quiet assembleRelease -PreadmeSign=release )
mkdir -p release
OUT=release/read-me-$VER.apk
cp android/app/build/outputs/apk/release/app-release.apk "$OUT"
require_release_cert "$OUT"
.venv-fdroid/bin/apksigcopier compare "$TESTED" --unsigned "$OUT" \
  || { echo "refused: the signed APK's content differs from the tested build"; rm -f "$OUT"; exit 1; }
( cd release && sha256sum "read-me-$VER.apk" > SHA256SUMS )
echo "ready: $OUT ($(cat release/SHA256SUMS | cut -c1-16)...), signed by $(cert_sha256 "$OUT" | cut -c1-16)..."
```

  Check `apksigcopier compare --help` on this machine first (rule 18; `pip install apksigcopier` into `.venv-fdroid` and pin the version in `fdroid-scan.sh`'s install line). Use the flags it actually has: the goal is to compare the two APKs ignoring signatures. If it has no such mode, compare `unzip -l` listings and per-entry CRCs excluding `META-INF/`, and ledger the substitution.

- [ ] **Step 4: Run** `bash scripts/tests/release-lib.test.sh && npm run test:scripts && (cd android && ./gradlew --quiet assembleRelease) && (cd android && ./gradlew --quiet assembleRelease -PreadmeSign=release; echo exit=$?)`.
  - Expected: the tests pass, and the default build passes.
  - The signed build fails with `release signing needs Gradle properties: READ_ME_STORE_FILE, ...`, because no keystore exists yet on this machine.

- [ ] **Step 5: Commit.** `feat(build): release signing from the owner's Gradle properties, cert-checked`

### Task 3: hermesc from source, and whether its bundle matches the prebuilt one

**Files:**
- Create: `scripts/build-hermesc.sh`
- Modify: `android/app/build.gradle` (the `react {}` block: `hermesCommand`)

**Interfaces:**
- Produces:
  - `scripts/build-hermesc.sh [outdir]` builds `hermesc` from `facebook/hermes` at tag `hermes-v$(node -p "require('hermes-compiler/package.json').version")` (today `hermes-v250829098.0.17`, which exists on GitHub, checked 2026-10-03). It prints the binary's path.
  - Gradle: `-PreadmeHermesc=<path>` sets `react { hermesCommand = <path> }`. Without it, the prebuilt is used (unchanged).

- [ ] **Step 1: Write the script.**

```bash
#!/usr/bin/env bash
# SPIKE-04 / REA-26: F-Droid will not run the prebuilt hermesc in node_modules/hermes-compiler.
# Build it from the Hermes source at the tag that matches the prebuilt version.
set -euo pipefail
cd "$(dirname "$0")/.."
V=$(node -p "require('hermes-compiler/package.json').version")
OUT=${1:-$HOME/.cache/read-me/hermesc-$V}
[ -x "$OUT/build/bin/hermesc" ] && { echo "$OUT/build/bin/hermesc"; exit 0; }
CMAKE_DIR=${ANDROID_HOME:-$HOME/Android/Sdk}/cmake/3.22.1/bin
mkdir -p "$OUT"
[ -d "$OUT/src" ] || git clone -q --depth 1 --branch "hermes-v$V" https://github.com/facebook/hermes.git "$OUT/src"
PATH="$CMAKE_DIR:$PATH" cmake -S "$OUT/src" -B "$OUT/build" -G Ninja -DCMAKE_BUILD_TYPE=Release >&2
PATH="$CMAKE_DIR:$PATH" cmake --build "$OUT/build" --target hermesc >&2
echo "$OUT/build/bin/hermesc"
```

- [ ] **Step 2: Run it.** `bash scripts/build-hermesc.sh`.
  - Expected: the last line is a path, and `<path> -version` prints a version that contains `250829098`.
  - Build failure that needs a system package (ICU, readline, python): it is a stop for the owner's `sudo`. Name the package, and continue with the other tasks meanwhile.
  - Not Ninja in the SDK's cmake dir: use `-G "Unix Makefiles"` and ledger it.

- [ ] **Step 3: Wire Gradle.** In the `react {}` block of `android/app/build.gradle`:

```groovy
    // REA-26: F-Droid builds hermesc from source (scripts/build-hermesc.sh) and passes it here.
    if (findProperty('readmeHermesc')) hermesCommand = findProperty('readmeHermesc')
```

- [ ] **Step 4: Compare the two bundles.**
  1. Build twice: `(cd android && ./gradlew --quiet assembleRelease)`, keeping `unzip -p .../app-release.apk assets/index.android.bundle > $S/prebuilt.hbc`.
  2. Then build with `-PreadmeHermesc=$(bash scripts/build-hermesc.sh)` and keep `$S/source.hbc`.
  3. Compare with `cmp $S/prebuilt.hbc $S/source.hbc && echo SAME`.

  Expected: SAME, since this is the same compiler source. Ledger the result.
  - SAME: our releases keep the prebuilt. The recipe uses the source build and still matches.
  - Different: ruling. `release-apk.sh` and `build-release.sh` always pass `-PreadmeHermesc`, so the published APK and F-Droid's build use the same compiler. Then re-run Step 4 to confirm that two source-built bundles match each other.

- [ ] **Step 5: Commit.** `feat(build): build hermesc from the Hermes source for F-Droid (SPIKE-04)`

### Task 4: Version 1.0.0, LICENSE, F-Droid recipe, scan and reproducibility

**Files:**
- Create:
  - `LICENSE`: already added (MIT, owner-confirmed 2026-10-03, commit before Task 1)
  - `fastlane/metadata/android/en-US/changelogs/1.txt`
  - `fdroid/io.loopstring.readme.yml`
  - `scripts/repro-check.sh`
- Modify: `package.json` (`version` 1.0.0, `repro` script), `android/app/build.gradle` (`versionName "1.0.0"`, versionCode stays 1), `scripts/fdroid-scan.sh`

**Interfaces:**
- Consumes: Task 3's `scripts/build-hermesc.sh` and `-PreadmeHermesc`.
- Produces:
  - `npm run repro`: exit 0 when two clean-clone builds in different directories match, ignoring signatures. It prints `repro: SAME` or the differing entries.
  - `fdroid/io.loopstring.readme.yml`, which `fdroid lint` accepts.

- [ ] **Step 1: Write the version, LICENSE and changelog.**
  - `changelogs/1.txt`, under 500 characters, says what 1.0.0 does: share an article or text, trim, listen offline with the phone's voice, screen-off controls, and the bridge for the Obsidian plugin. There are no network voices and no tracking.
  - Run `npm run notices`. The private app package is excluded, so expect no change; check.

- [ ] **Step 2: Add the scan of the tree after `npm ci`, and watch it fail.**
  - Add section `5. source scan after npm ci, with the recipe's deletions` to `fdroid-scan.sh`. It reads the deletion list from the recipe so the two cannot drift:

```bash
echo "== 5. source scan after npm ci, with the recipe's scandelete"
TREE=$(mktemp -d)
git archive HEAD | tar -x -C "$TREE" && ( cd "$TREE" && npm ci --ignore-scripts --silent ) || { echo "npm ci in export failed"; fail=1; }
"$VENV/bin/python" - fdroid/io.loopstring.readme.yml "$TREE" <<'PY' || fail=1
import sys, glob, os, shutil, yaml, logging
logging.basicConfig(level=logging.WARNING, format="%(levelname)s %(message)s")
from fdroidserver import common, scanner
recipe, tree = sys.argv[1], sys.argv[2]
build = yaml.safe_load(open(recipe))["Builds"][-1]
for pat in build.get("scandelete", []):
    for p in glob.glob(os.path.join(tree, pat)):
        shutil.rmtree(p) if os.path.isdir(p) else os.remove(p)
common.get_config()
n = scanner.scan_source(tree, build=common.Build(build))
print("source problems after npm ci:", n)
sys.exit(1 if n else 0)
PY
rm -rf "$TREE"
```

  - Run `scripts/fdroid-scan.sh` before the recipe exists.
    - Expected: section 5 fails, either because the recipe file is missing or because of the 46 SPIKE-04 errors. Ledger which.

- [ ] **Step 3: Write the recipe** `fdroid/io.loopstring.readme.yml`.
  - Node: `engines.node` is `>= 22.11.0`. Check the Debian release F-Droid's buildserver uses (`fdroidserver/buildserver/Dockerfile` or provisioning in fdroidserver 2.4.5: `grep -rn "FROM\|debian" .venv-fdroid/lib/python3*/site-packages/fdroidserver/ | head`), and that release's `nodejs` version (packages.debian.org). If it is under 22.11, the recipe downloads the official Node tarball in `sudo` and checks its SHA-256 against `SHASUMS256.txt` for that exact version.

```yaml
Categories:
  - Reading
License: MIT
AuthorName: Josh Shearer
SourceCode: https://github.com/JoshShearer/Read-Me
IssueTracker: https://github.com/JoshShearer/Read-Me/issues
Changelog: https://github.com/JoshShearer/Read-Me/releases

AutoName: Read Me

RepoType: git
Repo: https://github.com/JoshShearer/Read-Me.git

Builds:
  - versionName: 1.0.0
    versionCode: 1
    commit: v1.0.0
    subdir: android/app
    sudo:
      - apt-get update
      - apt-get install -y ninja-build cmake
      # node: Debian's if it meets engines.node, else the checksummed official tarball (Step 3 decides)
    init: cd ../.. && npm ci --ignore-scripts
    gradle:
      - yes
    srclibs:
      - hermes@hermes-v250829098.0.17
    prebuild:
      - cd ../.. && bash scripts/build-hermesc.sh $$hermes$$/../hermesc-build
    gradleprops:
      - readmeHermesc=$$hermes$$/../hermesc-build/build/bin/hermesc
    scandelete:
      - node_modules/hermes-compiler/hermesc
      - node_modules/fb-dotslash
      - node_modules/react-native/React/I18n
    ndk: 27.1.12297006

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 1.0.0
CurrentVersionCode: 1
```

  - **Adjust to the real tree.** Derive `scandelete` from section 5's output, not from this draft. Make `build-hermesc.sh` accept an existing source dir (the srclib) as its input, since F-Droid forbids network access in `prebuild`. Also create `fdroid/srclibs/hermes.yml` (`RepoType: git`, `Repo: https://github.com/facebook/hermes.git`) for the merge request.
  - **Check `gradleprops` exists** in fdroidserver 2.4.5's metadata schema (`grep -n gradleprops .venv-fdroid/lib/python3*/site-packages/fdroidserver/metadata.py`). If it does not, use a `prebuild` `sed` that writes `readmeHermesc=...` into `android/gradle.properties`.
  - **The unknown maven repos** SPIKE-04 found in `react-native/ReactAndroid/publish.gradle` and `react-native-safe-area-context/android/build.gradle` go under `scanignore` only if they are not used in our build (check: they are publishing config). Ledger the reason.

- [ ] **Step 4: Run** `scripts/fdroid-scan.sh` and `fdroid lint`. For lint, run in a temp fdroiddata layout: `mkdir -p $T/metadata && cp fdroid/io.loopstring.readme.yml $T/metadata/ && cd $T && <venv>/fdroid lint io.loopstring.readme`.
  - Expected: `== result: CLEAN`, section 5 reports `source problems after npm ci: 0`, and lint prints no errors.

- [ ] **Step 5: Write `scripts/repro-check.sh`.**

```bash
#!/usr/bin/env bash
# REA-26 / R-M13: two builds of HEAD from fresh clones in different directories must match, ignoring
# signatures (F-Droid builds elsewhere and copies our signature only if the content is identical).
set -euo pipefail
cd "$(dirname "$0")/.."
S=$(mktemp -d); trap 'rm -rf "$S"' EXIT
HERMESC=$(bash scripts/build-hermesc.sh)
for d in a b/deeper; do
  git clone -q "$PWD" "$S/$d"
  ( cd "$S/$d" && npm ci --silent && cd android && ./gradlew --quiet assembleRelease -PreadmeHermesc="$HERMESC" )
done
A=$S/a/android/app/build/outputs/apk/release/app-release.apk
B=$S/b/deeper/android/app/build/outputs/apk/release/app-release.apk
if .venv-fdroid/bin/apksigcopier compare "$A" --unsigned "$B" 2>/dev/null; then echo "repro: SAME"; exit 0; fi
echo "repro: DIFFERENT"
for f in $(unzip -Z1 "$A" | grep -v '^META-INF/'); do
  cmp -s <(unzip -p "$A" "$f") <(unzip -p "$B" "$f") || echo "  differs: $f"
done
exit 1
```

  The second clone is one level deeper, so a path embedded in a binary changes its length as well as its bytes. Use the `apksigcopier compare` flags verified in Task 2.

- [ ] **Step 6: Run** `npm run repro`. It is slow: two full builds.
  - Expected: `repro: SAME`.
  - If `.so` files differ: a build path or build-id leaked. Ruling: add `-ffile-prefix-map=${CMAKE_SOURCE_DIR}=.` and `-Wl,--build-id=none` through `externalNativeBuild { cmake { cppFlags/arguments } }` in `android/app/build.gradle`, then re-run.
  - If `index.android.bundle` or `assets/` differ: compare the two bundles with `diff <(strings a) <(strings b)` and fix the source of the variance.
  - Ledger every round.
  - Not established either way: F-Droid's own build server run (its machine, not this one). Say so in the docs.

- [ ] **Step 7: Commit.** `feat(build): 1.0.0, MIT license, F-Droid recipe, reproducible-build check`

### Task 5: R-M14 acceptance runs on the release content

**Files:**
- Create: `scripts/device-accept-share.sh` (`npm run device:accept-share`)
- Modify: `scripts/device-gap.sh` (`AIRPLANE=1`), `package.json`, `AGENTS.md` (gate lines and Known state)

**Interfaces:**
- Consumes: the device lib (`scripts/lib/device.sh`: `device_require_unlocked`, `device_install_release`, `device_clear_app`, `wait_log`, `centre_of`, `tap_desc`) and the existing share and Trim steps of `device-intake-e2e.sh` / `device-ui-e2e.sh`. Copy those exact adb lines; do not invent new selectors.
- Produces: `npm run device:accept-share`, and `AIRPLANE=1` for `device:gap`.

- [ ] **Step 1: Write `device-accept-share.sh`** (R-M14 run 1):
  1. Share a real https article the way a browser does: an `ACTION_SEND` text intent with the URL, the same intent `device:intake` sends.
  2. Wait for it to become readable, then open it.
  3. In Trim, cut one paragraph, then Done.
  4. Set 2.0x in the Reader if the Reader has a rate control. Otherwise set the default rate in Settings before opening; use whichever `device:ui` already drives.
  5. Play, and wait for 3 sentences (`playback start`, then progress in the logs).
  6. Screen off (`KEYCODE_SLEEP`) for 60 s. Screen on, and wait for the owner's unlock the way `device:playback` does.
  7. Check:
     - more sentences were spoken while the screen was off (log count)
     - the Reader shows the position past where it was before the lock
     - after force-stop and reopening, playback resumes at that sentence (`playback start item=N ... from=<idx>` with idx > 0)
     - the rate in the `playback start` line is `2.0`
  8. Scan logcat for item text and the URL path, as the other scripts do.

  Use the article URL `device:intake` uses. Run it once now against the current build.
  - Expected: FAIL only if a step's selector is wrong. Fix the script, never the app, unless the app is wrong; an app defect found here is reproduced first and fixed with a test (AGENTS.md 16).

- [ ] **Step 2: Add `AIRPLANE=1` to `device-gap.sh`** (R-M14 run 2):

```bash
if [ "${AIRPLANE:-0}" = 1 ]; then
  adb shell cmd connectivity airplane-mode enable
  trap 'adb shell cmd connectivity airplane-mode disable' EXIT
  [ "$(adb shell cmd connectivity airplane-mode | tr -d '\r')" = enabled ] || { echo "airplane mode did not turn on"; exit 1; }
fi
```

  Put it after the share step (the shared text needs no network, but install and setup happen first). Check that `cmd connectivity airplane-mode` printed `disabled` on the reference device on 2026-10-03. Merge the trap into the script's existing EXIT trap if it has one.

- [ ] **Step 3: Run the acceptance set on one build.**
  1. Build: `npm run build:release`, clean tree.
  2. Keep the APK as the tested build: `cp android/app/build/outputs/apk/release/app-release.apk release/tested-<sha>.apk` (gitignored).
  3. Run, in this order, so the screen-off runs come last and the phone needs one unlock:
     1. `npm run device:bridge`: R-M14 runs 3 and 4 (Obsidian through the bridge while backgrounded; the hostile-input set; the service survives).
     2. `npm run device:ui` and `npm run device:intake`: regression.
     3. `npm run device:accept-share`: run 1.
     4. `npm run device:playback`.
     5. `AIRPLANE=1 GAP_MINUTES=10 npm run device:gap`: run 2. This is the one 10-minute run.
  - Expected: each prints PASS. Record date, device, build and the gap numbers.

- [ ] **Step 4: Record.**
  - AGENTS.md "Known state", new entry "Release 1.0.0 (Phase 6b, REA-26)": the R-M14 runs 1-4 with date, device, build and gap numbers.
  - AGENTS.md "Quality gates": add `npm run device:accept-share`, `AIRPLANE=1 GAP_MINUTES=10 npm run device:gap` (the release run), `npm run repro`, `npm run release:apk`.
  - The R-M13 entry in the Phase 4 Known state ("R-M13 is partial ... Phase 6 completes it") is rewritten to say what the notices now cover.

- [ ] **Step 5: Commit.** `test(release): R-M14 acceptance runs 1-4 on the 1.0.0 content`

### Task 6: The owner's runbook

**Files:**
- Create: `docs/release.md`
- Modify: `CONTEXT.md`, only if it has a build or distribution section that is now wrong (`grep -n -i "release\|f-droid\|signing" CONTEXT.md`)

- [ ] **Step 1: Write `docs/release.md`.** Numbered owner steps, each with the exact command:
  1. Once: `npm run release:keystore`, where to back the key up, the four Gradle properties (`chmod 600 ~/.gradle/gradle.properties`), and commit `release/signing-cert.sha256`.
  2. Per release: build and test (Task 5's set), then `npm run release:apk release/tested-<sha>.apk`.
  3. Optional on the phone: a release-signed APK will not install over the debug-signed one. `adb uninstall io.loopstring.readme` deletes the app's data, then `adb install release/read-me-1.0.0.apk` and launch it. From then on, device scripts need the same key, or another uninstall.
  4. Tag and publish: check the draft release Claude prepared (`gh release view v1.0.0`), then `gh release edit v1.0.0 --draft=false`.
  5. Make the repo public, which F-Droid needs: `gh repo edit JoshShearer/Read-Me --visibility public --accept-visibility-change-consequences`. Check first that nothing private is in the history: `git log -p | grep -iE 'password|BEGIN .*PRIVATE'` returns nothing.
  6. F-Droid: fork `fdroid/fdroiddata` on GitLab, copy `fdroid/io.loopstring.readme.yml` to `metadata/` and `fdroid/srclibs/hermes.yml` to `srclibs/`, run `fdroid lint` and `fdroid build -v -l io.loopstring.readme` if you have the buildserver set up, then open the merge request. Add `Binaries: https://github.com/JoshShearer/Read-Me/releases/download/v%v/read-me-%v.apk` and `AllowedAPKSigningKeys: <release/signing-cert.sha256>` to the recipe at that point, so F-Droid publishes with your signature once its build matches.
  7. What is not established: F-Droid's build server has not run this recipe, and F-Droid may require the `react-android` and `hermes-android` AARs (prebuilt native libraries from Maven Central) to be built from source (SPIKE-04's open question). Reviewers will say.

- [ ] **Step 2: Check every command in it exists on this machine** (rule 18): `gh release --help | grep -c edit`, `gh repo edit --help | grep -c visibility`, and `npm run` lists `release:keystore` and `release:apk`.
  - Expected: each check finds its command.

- [ ] **Step 3: Commit.** `docs(release): owner runbook for signing, publishing and F-Droid`

### Task 7 (after the PR merges; needs the owner's keystore): the signed APK and the draft release

This task runs from `main` after merge, so the tag points at a merged commit. It stops at the start for the owner: `npm run release:keystore`, the Gradle properties, and a commit of `release/signing-cert.sha256` to `main` through a small PR (or into this branch before merge, if the owner does it earlier).

- [ ] **Step 1:** On `main`: `npm ci && npm run build:release` (clean). Then, using the tested APK from Task 5 if `main`'s app content equals that build (`apksigcopier compare`), run `npm run release:apk release/tested-<sha>.apk`.
  - Expected: `ready: release/read-me-1.0.0.apk ...`, with the signer equal to `release/signing-cert.sha256`.
  - If the content differs from the tested build (main moved after the runs), re-run Task 5 Step 3's set on the new build first.
- [ ] **Step 2:** `gh release create v1.0.0 --draft --target <full sha of the tested commit> --title "Read Me 1.0.0" --notes-file <scratch notes> release/read-me-1.0.0.apk release/SHA256SUMS`. The notes are the changelog plus the R-M14 record and the SHA-256. A draft is not public and creates no tag until the owner publishes.
  - Expected: `gh release view v1.0.0 --json isDraft` gives `true`.
- [ ] **Step 3:** Hand off to the owner: `docs/release.md` steps 4-6.
