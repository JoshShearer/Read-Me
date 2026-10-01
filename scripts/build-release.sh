#!/usr/bin/env bash
# npm run build:release - assembleRelease, then stamp the APK with the commit it was built from
# and whether the tree was clean, so a spike answer can prove which build it measured
# (AGENTS.md 17). .claude/ is excluded: tools write untracked files there.
set -euo pipefail
cd "$(dirname "$0")/.."
( cd android && ./gradlew --quiet assembleRelease )
STAMP=android/app/build/outputs/apk/release/app-release.apk.stamp
{
  git rev-parse HEAD
  if [ -z "$(git status --porcelain -- . ':(exclude).claude')" ]; then echo clean; else echo dirty; fi
} > "$STAMP"
echo "built $(sed -n 1p "$STAMP" | cut -c1-12) ($(sed -n 2p "$STAMP"))"
