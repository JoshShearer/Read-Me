#!/usr/bin/env bash
# npm run build:release - assembleRelease, then stamp the APK with the commit it was built from
# and whether the tree was clean, so a spike answer can prove which build it measured
# (AGENTS.md 17). .claude/ is excluded: tools write untracked files there.
set -euo pipefail
cd "$(dirname "$0")/.."
APK=android/app/build/outputs/apk/release/app-release.apk
STAMP=$APK.stamp
rm -f "$STAMP"
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
  hits=$(unzip -p "$APK" lib/arm64-v8a/libappmodules.so | grep -ac "$SPEC" || true)
  if [ "${hits:-0}" = 0 ]; then
    rm -f "$APK"
    echo "refused: libappmodules.so lacks the app codegen ($SPEC); the native build cache is" >&2
    echo "stale. Run: rm -rf android/app/.cxx android/app/build/intermediates/cxx, then rebuild" >&2
    exit 1
  fi
fi
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
