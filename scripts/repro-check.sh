#!/usr/bin/env bash
# REA-26 / R-M13: two builds of HEAD from fresh clones in different directories must match,
# ignoring signatures: F-Droid builds elsewhere and publishes our signature only if its build is
# identical. Each build follows the recipe: npm ci --ignore-scripts, the recipe's own prebuild
# (scripts/lib/recipe.sh; hermesc from source), its scandelete, then assembleRelease.
# npm run repro -- <apk> also compares that APK (the one we publish: npm run build:release,
# prebuilt hermesc, full npm ci) with the recipe's build.
# Same machine and caches, so it cannot prove a different machine matches.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -x .venv-fdroid/bin/python ] || { echo "no .venv-fdroid; run scripts/fdroid-scan.sh once"; exit 1; }
source scripts/lib/recipe.sh
OURS=${1:-}
[ -z "$OURS" ] || [ -f "$OURS" ] || { echo "no such APK: $OURS"; exit 1; }
V=$(node -p "require('hermes-compiler/package.json').version")
# The srclib: a Hermes checkout at the recipe's tag; its out/ caches the build across runs.
HERMES=$HOME/.cache/read-me/hermes-srclib-$V
[ -d "$HERMES/.git" ] || git clone -q --depth 1 --branch "hermes-v$V" https://github.com/facebook/hermes.git "$HERMES"
S=$(mktemp -d); trap 'rm -rf "$S"' EXIT
DELETE=$(.venv-fdroid/bin/python -c "import yaml; print('\n'.join(yaml.safe_load(open('fdroid/io.loopstring.readme.yml'))['Builds'][-1].get('scandelete', [])))")
for d in a b/deeper; do
  git clone -q "$PWD" "$S/$d"
  cp android/local.properties "$S/$d/android/" 2>/dev/null || true
  { ( cd "$S/$d" && npm ci --ignore-scripts --silent ) \
    && recipe_prebuild "$S/$d" "$HERMES" \
    && ( cd "$S/$d" && while read -r p; do [ -n "$p" ] && rm -rf $p; done <<< "$DELETE" ) \
    && ( cd "$S/$d/android" && ./gradlew --quiet --no-daemon assembleRelease ); } > "$S/build-${d%%/*}.log" 2>&1 \
    || { tail -20 "$S/build-${d%%/*}.log"; echo "repro: build in $d failed"; exit 1; }
  grep -q '^readmeHermesc=' "$S/$d/android/gradle.properties" || { echo "repro: the recipe's prebuild set no readmeHermesc"; exit 1; }
done
A=$S/a/android/app/build/outputs/apk/release/app-release.apk
B=$S/b/deeper/android/app/build/outputs/apk/release/app-release.apk
same() { PATH="${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools/37.0.0:$PATH" .venv-fdroid/bin/apksigcopier compare "$1" "$2" >/dev/null 2>&1; }
differs() {
  diff <(unzip -Z1 "$1" | sort) <(unzip -Z1 "$2" | sort) || true
  for f in $(unzip -Z1 "$1" | grep -v '^META-INF/'); do
    cmp -s <(unzip -p "$1" "$f") <(unzip -p "$2" "$f") || echo "  differs: $f"
  done
}
fail=0
if same "$A" "$B"; then echo "repro: SAME ($(stat -c %s "$A") bytes)"; else echo "repro: DIFFERENT"; differs "$A" "$B"; fail=1; fi
if [ -n "$OURS" ]; then
  if same "$A" "$OURS"; then echo "repro: the recipe's build equals $OURS"; else echo "repro: the recipe's build differs from $OURS"; differs "$A" "$OURS"; fail=1; fi
fi
exit $fail
