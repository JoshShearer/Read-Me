#!/usr/bin/env bash
# REA-26 / R-M13: two builds of HEAD from fresh clones in different directories must match,
# ignoring signatures: F-Droid builds elsewhere and publishes our signature only if its build is
# identical. Each build follows the recipe: npm ci --ignore-scripts, the recipe's scandelete,
# hermesc built from source. Same machine and caches, so it cannot prove a different machine matches.
set -euo pipefail
cd "$(dirname "$0")/.."
S=$(mktemp -d); trap 'rm -rf "$S"' EXIT
HERMESC=$(bash scripts/build-hermesc.sh)
DELETE=$(.venv-fdroid/bin/python -c "import yaml; print('\n'.join(yaml.safe_load(open('fdroid/io.loopstring.readme.yml'))['Builds'][-1].get('scandelete', [])))")
for d in a b/deeper; do
  git clone -q "$PWD" "$S/$d"
  cp android/local.properties "$S/$d/android/" 2>/dev/null || true
  ( cd "$S/$d" && npm ci --ignore-scripts --silent && while read -r p; do [ -n "$p" ] && rm -rf $p; done <<< "$DELETE" \
    && cd android && ./gradlew --quiet --no-daemon assembleRelease -PreadmeHermesc="$HERMESC" ) > "$S/build-${d%%/*}.log" 2>&1 \
    || { tail -20 "$S/build-${d%%/*}.log"; echo "repro: build in $d failed"; exit 1; }
done
A=$S/a/android/app/build/outputs/apk/release/app-release.apk
B=$S/b/deeper/android/app/build/outputs/apk/release/app-release.apk
if PATH="${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools/37.0.0:$PATH" .venv-fdroid/bin/apksigcopier compare "$A" "$B" >/dev/null 2>&1; then
  echo "repro: SAME ($(stat -c %s "$A") bytes)"; exit 0
fi
echo "repro: DIFFERENT"
diff <(unzip -Z1 "$A" | sort) <(unzip -Z1 "$B" | sort) || true
for f in $(unzip -Z1 "$A" | grep -v '^META-INF/'); do
  cmp -s <(unzip -p "$A" "$f") <(unzip -p "$B" "$f") || echo "  differs: $f"
done
exit 1
