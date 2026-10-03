#!/usr/bin/env bash
# The recipe's prebuild must leave readmeHermesc in android/gradle.properties (REA-26 review C1).
set -uo pipefail
cd "$(dirname "$0")/../.."
source scripts/lib/recipe.sh
$(recipe_py) -c 'import yaml' 2>/dev/null || { echo "SKIP: no PyYAML here"; exit 0; }
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mkdir -p "$T/repo" "$T/hermes/out/build/bin"
git archive HEAD | tar -x -C "$T/repo"
printf '#!/bin/sh\necho fake\n' > "$T/hermes/out/build/bin/hermesc"; chmod +x "$T/hermes/out/build/bin/hermesc"
# build-hermesc.sh needs node_modules/hermes-compiler for its version; a stub is enough here.
mkdir -p "$T/repo/node_modules/hermes-compiler" && echo '{"version":"0.0.0-test"}' > "$T/repo/node_modules/hermes-compiler/package.json"
before=$(grep -c '' "$T/repo/android/gradle.properties")
recipe_prebuild "$T/repo" "$T/hermes" >/dev/null 2>&1
fail=0
grep -qx "readmeHermesc=$T/hermes/out/build/bin/hermesc" "$T/repo/android/gradle.properties" \
  && echo "ok: readmeHermesc set in android/gradle.properties" || { echo "FAIL: readmeHermesc not set"; fail=1; }
grep -qx 'android.newDsl=false' "$T/repo/android/gradle.properties" \
  && echo "ok: the last existing property is intact" || { echo "FAIL: android.newDsl corrupted"; fail=1; }
exit $fail
