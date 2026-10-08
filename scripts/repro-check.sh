#!/usr/bin/env bash
# REA-26 / R-M13: two builds of HEAD from fresh clones in different directories must match,
# ignoring signatures: F-Droid builds elsewhere and publishes our signature only if its build is
# identical. Each build follows the recipe: npm ci --ignore-scripts, the recipe's own prebuild
# (scripts/lib/recipe.sh; hermesc from the submodule), its scandelete, then assembleRelease. One
# ABI's build entry (REA-40): REPRO_ABI, default arm64-v8a.
# npm run repro -- <apk> also compares that APK (that ABI's from npm run build:release, prebuilt
# hermesc, full npm ci) with the recipe's build.
# Same machine and caches, so it cannot prove a different machine matches.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -x .venv-fdroid/bin/python ] || { echo "no .venv-fdroid; run scripts/fdroid-scan.sh once"; exit 1; }
source scripts/lib/recipe.sh
OURS=${1:-}
[ -z "$OURS" ] || [ -f "$OURS" ] || { echo "no such APK: $OURS"; exit 1; }
ABI=${REPRO_ABI:-arm64-v8a}
S=$(mktemp -d); trap 'rm -rf "$S"' EXIT
DELETE=$(.venv-fdroid/bin/python -c "import yaml; print('\n'.join([b for b in yaml.safe_load(open('fdroid/io.loopstring.readme.yml'))['Builds'] if b['binary'].endswith('-$ABI.apk')][0].get('scandelete', [])))")
for d in a b/deeper; do
  git clone -q "$PWD" "$S/$d"
  git -C "$S/$d" submodule update -q --init
  cp android/local.properties "$S/$d/android/" 2>/dev/null || true
  { ( cd "$S/$d" && npm ci --ignore-scripts --silent ) \
    && recipe_prebuild "$S/$d" "$ABI" \
    && ( cd "$S/$d" && while read -r p; do [ -n "$p" ] && rm -rf $p; done <<< "$DELETE" ) \
    && ( cd "$S/$d/android" && ./gradlew --quiet --no-daemon assembleRelease ); } > "$S/build-${d%%/*}.log" 2>&1 \
    || { tail -20 "$S/build-${d%%/*}.log"; echo "repro: build in $d failed"; exit 1; }
  grep -q '^readmeHermesc=' "$S/$d/android/gradle.properties" || { echo "repro: the recipe's prebuild set no readmeHermesc"; exit 1; }
done
A=$S/a/android/app/build/outputs/apk/release/app-$ABI-release.apk
B=$S/b/deeper/android/app/build/outputs/apk/release/app-$ABI-release.apk
fail=0
if apk_same "$A" "$B"; then echo "repro: SAME ($(stat -c %s "$A") bytes)"; else echo "repro: DIFFERENT"; apk_differs "$A" "$B"; fail=1; fi
if [ -n "$OURS" ]; then
  if apk_same "$A" "$OURS"; then echo "repro: the recipe's build equals $OURS"; else echo "repro: the recipe's build differs from $OURS"; apk_differs "$A" "$OURS"; fail=1; fi
fi
exit $fail
