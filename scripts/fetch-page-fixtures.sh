#!/usr/bin/env bash
# One-time download of the real-page extraction fixtures (dev machine only; tests never
# download). Re-running replaces them; commit the result and update SOURCES.md's date.
set -euo pipefail
cd "$(dirname "$0")/.."
D=__tests__/fixtures/pages
mkdir -p "$D"
UA="Mozilla/5.0 (X11; Linux x86_64) ReadMe-fixtures"
curl -sSfL -A "$UA" https://www.weather.gov/safety/lightning-science-overview -o "$D/weather-gov-lightning.html"
curl -sSfL -A "$UA" https://www.weather.gov/safety/flood-turn-around-dont-drown -o "$D/weather-gov-flood.html"
curl -sSfL -A "$UA" https://www.gutenberg.org/cache/epub/1342/pg1342-images.html -o "$D/gutenberg-1342.html"
wc -c "$D"/*.html
