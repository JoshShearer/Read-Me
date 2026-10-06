#!/usr/bin/env bash
# R-M13 / AGENTS.md 14: F-Droid-style checks. Runs all five and exits 0 only if all pass.
#   1. fdroidserver source scan of a clean export of HEAD (what F-Droid's builder sees).
#   2. fdroidserver binary scan of each ABI's release APK (known non-free classes).
#   3. No Play Services / Firebase / Crashlytics in the resolved release runtime classpath.
#   4. Production npm licenses (scripts/check-licenses.mjs, ADR 0002).
#   5. fdroidserver source scan of the tree after npm ci, with the recipe's deletions.
set -uo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/release.sh
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
VENV=.venv-fdroid
{ [ -x "$VENV/bin/fdroid" ] && [ -x "$VENV/bin/apksigcopier" ]; } || { python3 -m venv "$VENV" && "$VENV/bin/pip" -q install fdroidserver==2.4.5 apksigcopier==1.1.1; } || exit 1
fail=0
SRC=$(mktemp -d); DEPS=$(mktemp)
trap 'rm -rf "$SRC" "$DEPS"' EXIT

echo "== 1. source scan of clean HEAD export"
# An export that fails leaves $SRC empty, and an empty tree scans as "0 problems": fail closed.
if ! git archive HEAD | tar -x -C "$SRC" || [ ! -f "$SRC/package.json" ]; then
  echo "export of HEAD failed; source scan not run"; fail=1
else "$VENV/bin/python" - "$SRC" <<'PY' || fail=1
import sys, logging
logging.basicConfig(level=logging.WARNING, format="%(levelname)s %(message)s")
from fdroidserver import common, scanner
common.get_config()
n = scanner.scan_source(sys.argv[1])
print("source problems:", n)
sys.exit(1 if n else 0)
PY
fi

echo "== 2. APK binary scan"
for ABI in $READ_ME_ALL_ABIS; do
  APK=$(readme_apk "$ABI")
  if [ ! -f "$APK" ]; then echo "missing $APK; run npm run build:release"; fail=1
  elif [ "$(sed -n 1p "$APK.stamp" 2>/dev/null)" != "$(git rev-parse HEAD)" ] \
       || [ "$(sed -n 2p "$APK.stamp" 2>/dev/null)" != clean ]; then
    echo "$APK not built from a clean HEAD (stamp: $(tr '\n' ' ' < "$APK.stamp" 2>/dev/null)); run npm run build:release"; fail=1
  else
    "$VENV/bin/fdroid" scanner --exit-code "$APK" || fail=1
    require_no_extra_signing_blocks "$APK" && echo "$ABI: no extra signing blocks" || fail=1
  fi
done

echo "== 3. non-free Gradle dependencies (resolved tree)"
# Capture first: a failed gradlew piped straight into grep would read as "none found".
if ! ( cd android && ./gradlew --quiet :app:dependencies --configuration releaseRuntimeClasspath ) > "$DEPS"; then
  echo "gradlew dependencies failed"; fail=1
elif grep -niE "com\.google\.android\.gms|firebase|crashlytics|play-services|com\.google\.android\.play" "$DEPS"; then
  echo "non-free dependency found"; fail=1
else echo "none"; fi

echo "== 4. npm production licenses"
node scripts/check-licenses.mjs || fail=1

echo "== 5. source scan after npm ci, with the recipe's scandelete and scanignore"
# SPIKE-04: a git export misses node_modules, which F-Droid's builder has after the recipe's
# init. Apply the recipe's own deletions so the scan and the recipe cannot drift apart.
TREE=$(mktemp -d)
if ! { git archive HEAD | tar -x -C "$TREE" && ( cd "$TREE" && npm ci --ignore-scripts --silent ); }; then
  echo "npm ci in the export failed; scan not run"; fail=1
else "$VENV/bin/python" - fdroid/io.loopstring.readme.yml "$TREE" <<'PY' || fail=1
import sys, glob, os, shutil, yaml, logging, argparse
logging.basicConfig(level=logging.ERROR, format="%(levelname)s %(message)s")
from fdroidserver import common, metadata, scanner
# The scanner logs each problem only when its options say verbose.
common.get_options = lambda: argparse.Namespace(verbose=True, json=False)
recipe, tree = sys.argv[1], sys.argv[2]
raw = yaml.safe_load(open(recipe))["Builds"][-1]
for pat in raw.get("scandelete", []):
    for p in glob.glob(os.path.join(tree, pat)):
        shutil.rmtree(p) if os.path.isdir(p) else os.remove(p)
build = metadata.Build()
build.scanignore = raw.get("scanignore", [])
common.get_config()
n = scanner.scan_source(tree, build)
print("source problems after npm ci:", n)
sys.exit(1 if n else 0)
PY
fi
rm -rf "$TREE"

echo "== result: $([ $fail -eq 0 ] && echo CLEAN || echo PROBLEMS)"
exit $fail
