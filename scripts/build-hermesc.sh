#!/usr/bin/env bash
# SPIKE-04 / REA-26: F-Droid will not run the prebuilt hermesc in node_modules/hermes-compiler.
# Build it from the Hermes source and print its path. The source is the external/hermes
# submodule (REA-40: F-Droid wants submodules, not srclibs), pinned at the tag that matches
# hermes-compiler; HERMES_SRC names another checkout. The build goes outside the repository
# (default ~/.cache/read-me), so F-Droid's scanner never sees its objects.
set -euo pipefail
cd "$(dirname "$0")/.."
V=$(node -p "require('hermes-compiler/package.json').version")
OUT=${1:-$HOME/.cache/read-me/hermesc-$V}
BIN=$OUT/build/bin/hermesc
[ -x "$BIN" ] && { echo "$BIN"; exit 0; }
SRC=${HERMES_SRC:-external/hermes}
if [ -z "${HERMES_SRC:-}" ] && [ ! -f "$SRC/CMakeLists.txt" ]; then
  git submodule update --init external/hermes >&2
fi
# A checkout of another version would compile the bundle with a different Hermes than the
# runtime ships (an RN upgrade that forgot to move the submodule). Its own npm package says
# which version it is; a tag would need the clone to have fetched tags.
got=$(node -p "require('./$SRC/npm/hermes-compiler/package.json').version" 2>/dev/null || echo "unknown")
[ "$got" = "$V" ] || { echo "refused: $SRC is Hermes $got, not $V (hermes-compiler's version)" >&2; exit 1; }
mkdir -p "$OUT"
# The SDK's CMake ships Ninja; F-Droid's recipe installs both from Debian instead.
CMAKE_BIN=${ANDROID_HOME:-$HOME/Android/Sdk}/cmake/3.22.1/bin
[ -d "$CMAKE_BIN" ] && PATH="$CMAKE_BIN:$PATH"
cmake -S "$SRC" -B "$OUT/build" -G Ninja -DCMAKE_BUILD_TYPE=Release >&2
cmake --build "$OUT/build" --target hermesc >&2
echo "$BIN"
