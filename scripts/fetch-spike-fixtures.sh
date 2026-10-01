#!/usr/bin/env bash
# One-time download of SPIKE-02 fixtures (dev machine only; the app never fetches these).
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p spikes/fixtures
UA="Mozilla/5.0 (X11; Linux x86_64) ReadMe-spike-fixtures"
curl -sSfL -A "$UA" https://en.wikipedia.org/wiki/Speech_synthesis -o spikes/fixtures/wikipedia-speech-synthesis.html
curl -sSfL -A "$UA" https://developer.mozilla.org/en-US/docs/Web/API/SpeechSynthesis -o spikes/fixtures/mdn-speechsynthesis.html
curl -sSfL -A "$UA" https://www.gutenberg.org/cache/epub/1342/pg1342-images.html -o spikes/fixtures/gutenberg-pride-and-prejudice.html
ls -l spikes/fixtures
