#!/usr/bin/env bash
# npm run build:release - assembleRelease, then stamp the APK with the commit it was built from
# and whether the tree was clean, so a spike answer can prove which build it measured
# (AGENTS.md 17). .claude/ is excluded: tools write untracked files there.
set -euo pipefail
cd "$(dirname "$0")/.."
# R-M13: the Licenses screen's asset must match this tree's dependencies.
node scripts/make-notices.mjs --check || { echo "refused: run npm run notices and commit the asset" >&2; exit 1; }
APK=android/app/build/outputs/apk/release/app-release.apk
STAMP=$APK.stamp
rm -f "$STAMP"
# A spec change needs a fresh codegen; a CMake cache from before it silently keeps the old one
# (the 964fef0 trap). The stamp records which spec the cache was built from.
SPEC_TS=src/native/NativeReadMeSpeech.ts
CXX_STAMP=android/app/.cxx/.readme-spec-stamp
if [ -d android/app/.cxx ] && { [ ! -f "$CXX_STAMP" ] || [ "$SPEC_TS" -nt "$CXX_STAMP" ]; }; then
  rm -rf android/app/.cxx android/app/build/intermediates/cxx
  echo "the TurboModule spec changed since the native build cache was made; cleared it"
fi
( cd android && ./gradlew --quiet assembleRelease )
# The entry file is a Gradle property, which ORG_GRADLE_PROJECT_readmeEntryFile or a
# gradle.properties can set without anyone passing it. A product build must never carry the
# devcheck bundle, so look for its marker in the bundle that was actually packaged.
# grep -c reads the whole stream: grep -q would exit early, unzip would die of SIGPIPE, and
# under pipefail the check would read as no match (the Phase 0 F1 trap).
# A TurboModule needs the app's codegen compiled into libappmodules.so. A CMake cache
# (android/app/.cxx) configured before package.json had codegenConfig silently leaves it out,
# and the app then dies at TurboModuleRegistry.getEnforcing (reproduced 2026-10-02, 964fef0).
SPEC=$(node -e "console.log((require('./package.json').codegenConfig||{}).name||'')")
if [ -n "$SPEC" ]; then
  SO=$(mktemp)
  unzip -p "$APK" lib/arm64-v8a/libappmodules.so > "$SO"
  missing=""
  # Every method of the spec interface must be compiled in, not only the module name: a cache
  # that predates one method leaves the app crashing when JS first calls it. Only names of 9+
  # characters are checkable: clang writes shorter literals as immediates, so "getItem" is in
  # no string table even in a good build (seen 2026-10-02 on the Phase 3 build).
  for m in "$SPEC" $(node -e "
    const s=require('fs').readFileSync('$SPEC_TS','utf8');
    const body=s.slice(s.indexOf('interface Spec'));
    console.log([...body.matchAll(/^  (\w{9,})\(/gm)].map(x=>x[1]).join(' '))"); do
    [ "$(grep -ac "$m" "$SO" || true)" = 0 ] && missing="$missing $m"
  done
  rm -f "$SO"
  if [ -n "$missing" ]; then
    rm -f "$APK"
    echo "refused: libappmodules.so lacks the app codegen for:$missing; the native build cache is" >&2
    echo "stale. Run: rm -rf android/app/.cxx android/app/build/intermediates/cxx, then rebuild" >&2
    exit 1
  fi
fi
# Only a cache that produced a complete codegen is marked current; a refused build stays
# stale so the next run clears it.
mkdir -p android/app/.cxx && touch "$CXX_STAMP"
if ! unzip -l "$APK" assets/index.android.bundle >/dev/null 2>&1; then
  rm -f "$APK"
  echo "refused: no assets/index.android.bundle in the APK, so its entry cannot be checked" >&2
  exit 1
fi
marker=$(unzip -p "$APK" assets/index.android.bundle | grep -ac DEVCHECK_DONE || true)
if [ "${marker:-0}" != 0 ]; then
  rm -f "$APK"
  echo "refused: the bundle is the devcheck entry, not index.js (is readmeEntryFile set in the" >&2
  echo "environment or a gradle.properties?); APK deleted, nothing stamped" >&2
  exit 1
fi
{
  git rev-parse HEAD
  if [ -z "$(git status --porcelain -- . ':(exclude).claude')" ]; then echo clean; else echo dirty; fi
} > "$STAMP"
echo "built $(sed -n 1p "$STAMP" | cut -c1-12) ($(sed -n 2p "$STAMP"))"
