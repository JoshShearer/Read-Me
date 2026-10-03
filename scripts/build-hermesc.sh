#!/usr/bin/env bash
# SPIKE-04 / REA-26: F-Droid will not run the prebuilt hermesc in node_modules/hermes-compiler.
# Build it from the Hermes source at the tag that matches the prebuilt version, and print its
# path. HERMES_SRC names an existing checkout (F-Droid's srclib; no network in prebuild);
# without it the tag is cloned into the output directory.
set -euo pipefail
cd "$(dirname "$0")/.."
V=$(node -p "require('hermes-compiler/package.json').version")
OUT=${1:-$HOME/.cache/read-me/hermesc-$V}
BIN=$OUT/build/bin/hermesc
[ -x "$BIN" ] && { echo "$BIN"; exit 0; }
SRC=${HERMES_SRC:-$OUT/src}
mkdir -p "$OUT"
if [ ! -d "$SRC" ]; then
  git clone -q --depth 1 --branch "hermes-v$V" https://github.com/facebook/hermes.git "$SRC" >&2
fi
# A checkout at another tag would compile the bundle with a different Hermes than the
# runtime ships (an RN upgrade that forgot the recipe's srclib pin).
at=$(git -C "$SRC" describe --tags --exact-match 2>/dev/null || echo "an untagged commit")
[ "$at" = "hermes-v$V" ] || { echo "refused: $SRC is at $at, not hermes-v$V (hermes-compiler's version)" >&2; exit 1; }
# The SDK's CMake ships Ninja; F-Droid's recipe installs both from Debian instead.
CMAKE_BIN=${ANDROID_HOME:-$HOME/Android/Sdk}/cmake/3.22.1/bin
[ -d "$CMAKE_BIN" ] && PATH="$CMAKE_BIN:$PATH"
cmake -S "$SRC" -B "$OUT/build" -G Ninja -DCMAKE_BUILD_TYPE=Release >&2
cmake --build "$OUT/build" --target hermesc >&2
echo "$BIN"
