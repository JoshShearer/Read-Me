#!/usr/bin/env bash
# One-time download of the public-domain playback corpus (dev machine only).
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p android/app/src/main/assets/spike
curl -sSfL -A "Mozilla/5.0 ReadMe-spike-corpus" https://www.gutenberg.org/cache/epub/1342/pg1342.txt \
  | tr -d '\r' | awk '/^\*\*\* START OF/{on=1; next} /^\*\*\* END OF/{on=0} on' \
  > android/app/src/main/assets/spike/corpus.txt
wc -c android/app/src/main/assets/spike/corpus.txt
