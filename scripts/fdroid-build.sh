#!/usr/bin/env bash
# REA-38 / R-M13: npm run fdroid:build -- <tested apk>. F-Droid's own build job, in F-Droid's
# buildserver image, builds HEAD from a clean clone with fdroid/io.loopstring.readme.yml; the
# result must equal the tested APK, signatures aside. v1.0.0 passed npm run repro (two clones
# on this machine) and still differed here: the build host's IP, the Gradle cache path and
# stale bundle output never vary between two clones of one machine. A match is recorded in
# release/fdroid-verified, which release:apk requires. Needs docker and network; about 20 min.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/recipe.sh
source scripts/lib/release.sh
OURS=${1:?usage: npm run fdroid:build -- <the tested APK>}
[ -f "$OURS" ] || { echo "no such APK: $OURS"; exit 1; }
[ -z "$(git status --porcelain -- . ':(exclude).claude')" ] || { echo "refused: the tree is dirty; F-Droid builds the commit"; exit 1; }
command -v docker >/dev/null || { echo "refused: no docker"; exit 1; }
# Checked now, not after the 20-minute build, where a missing tool would read as a mismatch.
[ -x .venv-fdroid/bin/apksigcopier ] || { echo "refused: no .venv-fdroid/bin/apksigcopier; run scripts/fdroid-scan.sh once"; exit 1; }
$(recipe_py) -c 'import yaml' 2>/dev/null || { echo "refused: no PyYAML for $(recipe_py)"; exit 1; }
IMAGE=registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie
APPID=io.loopstring.readme
VC=$($(recipe_py) -c "import yaml; print(yaml.safe_load(open('fdroid/$APPID.yml'))['Builds'][-1]['versionCode'])")
HEAD_SHA=$(git rev-parse HEAD)
CACHE=$HOME/.cache/read-me/fdroiddata
if [ -d "$CACHE/.git" ]; then git -C "$CACHE" pull -q --ff-only; else git clone -q --depth 1 https://gitlab.com/fdroid/fdroiddata.git "$CACHE"; fi
W=$(mktemp -d)
# The container writes as root; remove its files with its own uid.
trap 'docker run --rm -v "$W:/w" alpine:3 rm -rf /w/fdroiddata /w/src >/dev/null 2>&1; rm -rf "$W"' EXIT
git clone -q "$CACHE" "$W/fdroiddata"
git clone -q "$PWD" "$W/src"
recipe_local_metadata "fdroid/$APPID.yml" /src "$HEAD_SHA" > "$W/fdroiddata/metadata/$APPID.yml"
cp fdroid/srclibs/*.yml "$W/fdroiddata/srclibs/"
LOG=${FDROID_BUILD_LOG:-.claude/scratch/fdroid-build-$(git rev-parse --short HEAD).log}
mkdir -p "$(dirname "$LOG")"
echo "fdroid:build: F-Droid's build job on $(git rev-parse --short HEAD) in $IMAGE (log: $LOG)"
docker pull -q "$IMAGE" >/dev/null
docker run --rm -e CI_PROJECT_DIR=/builds/fdroiddata -e ANDROID_HOME=/opt/android-sdk -e TERM=dumb \
  -v "$W/fdroiddata:/builds/fdroiddata" -v "$W/src:/src:ro" -v "$PWD/scripts/fdroid/ci-build.sh:/ci-build.sh:ro" \
  "$IMAGE" bash /ci-build.sh "$APPID:$VC" > "$LOG" 2>&1 \
  || fdroid_build_failed "$LOG"
THEIRS=$W/fdroiddata/tmp/${APPID}_$VC.apk
[ -f "$THEIRS" ] || { echo "fdroid:build: no APK from F-Droid's build (log: $LOG)"; exit 1; }
cp "$THEIRS" "$(dirname "$LOG")/fdroid-built-$(git rev-parse --short HEAD).apk"
if apk_same_unsigned "$OURS" "$THEIRS"; then
  mkdir -p release && record_fdroid_verified "$OURS"
  echo "fdroid:build: SAME: F-Droid's build equals $OURS (recorded in release/fdroid-verified)"
else
  echo "fdroid:build: DIFFERENT from $OURS"; apk_differs "$OURS" "$THEIRS"; exit 1
fi
