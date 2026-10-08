#!/usr/bin/env bash
# The recipe's prebuild must leave readmeHermesc in android/gradle.properties (REA-26 review C1),
# and each ABI's entry must build that ABI only (REA-40).
set -uo pipefail
cd "$(dirname "$0")/../.."
source scripts/lib/recipe.sh
$(recipe_py) -c 'import yaml' 2>/dev/null || { echo "SKIP: no PyYAML here"; exit 0; }
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mkdir -p "$T/repo"
git archive HEAD | tar -x -C "$T/repo"
# build-hermesc.sh needs node_modules/hermes-compiler for its version; a stub is enough here, and
# a hermesc already in its cache (under $HOME) stands in for building one.
mkdir -p "$T/repo/node_modules/hermes-compiler" && echo '{"version":"0.0.0-test"}' > "$T/repo/node_modules/hermes-compiler/package.json"
FAKE=$T/home/.cache/read-me/hermesc-0.0.0-test/build/bin/hermesc
mkdir -p "$(dirname "$FAKE")" && printf '#!/bin/sh\necho fake\n' > "$FAKE" && chmod +x "$FAKE"
HOME=$T/home recipe_prebuild "$T/repo" x86 >/dev/null 2>&1
fail=0
grep -qx "readmeHermesc=$FAKE" "$T/repo/android/gradle.properties" \
  && echo "ok: readmeHermesc set in android/gradle.properties" || { echo "FAIL: readmeHermesc not set"; fail=1; }
[ "$(grep '^reactNativeArchitectures=' "$T/repo/android/gradle.properties" | tail -1)" = reactNativeArchitectures=x86 ] \
  && echo "ok: the x86 entry builds only x86 (its line comes last)" || { echo "FAIL: the x86 entry does not select x86"; fail=1; }
grep -qx 'reactNativeDevServerIp=localhost' "$T/repo/android/gradle.properties" \
  && echo "ok: the last existing property is intact" || { echo "FAIL: reactNativeDevServerIp corrupted"; fail=1; }
# REA-40: four build entries, one per ABI. Each must build the ABI its binary: names, with the
# versionCode build.gradle gives that ABI's split, or F-Droid's APK and ours differ in both.
$(recipe_py) - <<'PY' && echo "ok: each build entry's ABI, versionCode and binary agree with build.gradle" || { echo "FAIL: a build entry disagrees with build.gradle"; fail=1; }
import re, yaml
m = yaml.safe_load(open("fdroid/io.loopstring.readme.yml"))
g = open("android/app/build.gradle").read()
base = int(re.search(r"^\s+versionCode (\d+)$", g, re.M).group(1))
codes = {a: int(c) for a, c in re.findall(r"'([\w-]+)': (\d)", re.search(r"readmeAbiCodes = \[(.*?)\]", g).group(1))}
assert len(m["Builds"]) == len(codes) == 4, (len(m["Builds"]), codes)
for b in m["Builds"]:
    abi = re.search(r"-([\w-]+)\.apk$", b["binary"]).group(1)
    assert b["versionCode"] == 10 * base + codes[abi], (abi, b["versionCode"])
    assert "reactNativeArchitectures=%s\\n" % abi in b["prebuild"][-1], (abi, b["prebuild"][-1])
    assert b["submodules"] is True, abi
    assert "srclibs" not in b, abi
assert m["VercodeOperation"] == ["10 * %%c + %d" % codes[a] for a in sorted(codes, key=codes.get)], m["VercodeOperation"]
assert m["CurrentVersionCode"] == max(b["versionCode"] for b in m["Builds"])
PY
# The Hermes submodule must be the version npm's hermesc is (critique A F2): an RN upgrade that
# forgets the submodule would build the bundle with the old compiler. CI checks out without it.
if [ -f external/hermes/npm/hermes-compiler/package.json ]; then
  want=$(node -p "require('./node_modules/hermes-compiler/package.json').version")
  got=$(node -p "require('./external/hermes/npm/hermes-compiler/package.json').version")
  [ "$got" = "$want" ] && echo "ok: the Hermes submodule is $want" || { echo "FAIL: the submodule is Hermes $got, npm has $want"; fail=1; }
else echo "SKIP: the Hermes submodule is not checked out here"; fi
# build-hermesc.sh refuses a source checkout of another Hermes version.
mkdir -p "$T/wrongsrc/npm/hermes-compiler" && echo '{"version":"0.0.1"}' > "$T/wrongsrc/npm/hermes-compiler/package.json"
out=$(HERMES_SRC="$T/wrongsrc" bash scripts/build-hermesc.sh "$T/wrongout" 2>&1 || true)
if grep -q "refused: .* is Hermes 0.0.1" <<<"$out"; then echo "ok: build-hermesc refuses another Hermes version"
else echo "FAIL: build-hermesc did not refuse another version: $out"; fail=1; fi
# F-Droid strips signing config before building (fdroidserver remove_signing_keys); every
# signingConfig line must match its pattern, or F-Droid's build gets signed with a stray key.
if [ -x .venv-fdroid/bin/python ]; then
  mkdir -p "$T/strip/android/app" && cp android/app/build.gradle "$T/strip/android/app/"
  .venv-fdroid/bin/python -c "import sys; from fdroidserver import common; common.remove_signing_keys(sys.argv[1])" "$T/strip/android" 2>/dev/null
  if grep -n 'signingConfig' "$T/strip/android/app/build.gradle"; then
    echo "FAIL: signing config survives F-Droid's stripping"; fail=1
  else echo "ok: F-Droid's stripping removes every signingConfig"; fi
else echo "SKIP: F-Droid stripping (no .venv-fdroid)"; fi
# fdroid:build (REA-38) runs F-Droid's build job on a local clone: the recipe's last build must
# point at that clone and commit, and must not name our release download (it does not exist yet).
recipe_local_metadata fdroid/io.loopstring.readme.yml /src 0123456789abcdef0123456789abcdef01234567 > "$T/local.yml" 2>"$T/local.err"
$(recipe_py) - "$T/local.yml" <<'PY' && echo "ok: local metadata points at the clone and commit" || { echo "FAIL: local metadata: $(cat "$T/local.err")"; fail=1; }
import sys, yaml
m = yaml.safe_load(open(sys.argv[1]))
assert m["Repo"] == "/src", m.get("Repo")
assert len(m["Builds"]) == 4
for b in m["Builds"]:
    assert b["commit"] == "0123456789abcdef0123456789abcdef01234567"
    assert "binary" not in b
assert "Binaries" not in m and "AllowedAPKSigningKeys" not in m
PY
# fdroidserver reads YAML 1.2, where "yes" is the string the gradle: entry needs; a YAML 1.1
# round trip writes it back as true, and F-Droid then builds a flavor named "True".
diff <(grep -vE '^(Repo|Binaries|AllowedAPKSigningKeys):|^    (commit|binary):|^#|^ *#' fdroid/io.loopstring.readme.yml) <(grep -vE '^(Repo|Binaries|AllowedAPKSigningKeys):|^    (commit|binary):|^#|^ *#' "$T/local.yml") >/dev/null \
  && echo "ok: local metadata keeps every other recipe line as written" || { echo "FAIL: local metadata rewrote recipe lines"; fail=1; }
$(recipe_py) - <<'PY' && echo "ok: every build names our signed APK and the version is package.json's" || { echo "FAIL: a build's binary or version is wrong"; fail=1; }
import json, yaml
m = yaml.safe_load(open("fdroid/io.loopstring.readme.yml"))
v = json.load(open("package.json"))["version"]
assert m.get("AllowedAPKSigningKeys"), "no AllowedAPKSigningKeys"
for b in m["Builds"]:
    assert b["binary"].startswith("https://github.com/JoshShearer/Read-Me/releases/download/v%v/read-me-%v-"), b["binary"]
    assert b["versionName"] == v, (b["versionName"], v)
assert m["CurrentVersion"] == v
PY
# REA-38 review: a build that fails before fdroid prints ERROR (apt, a clone, a checksum) must
# still say so and name its log, under the script's set -euo pipefail.
printf 'E: Failed to fetch something\n' > "$T/early.log"
out=$(bash -c 'set -euo pipefail; source scripts/lib/recipe.sh; fdroid_build_failed "$1"' _ "$T/early.log" 2>&1 || true)
grep -q "F-Droid's build FAILED (log: $T/early.log)" <<<"$out" && grep -q 'E: Failed to fetch' <<<"$out" \
  && echo "ok: an early build failure is reported with its log" || { echo "FAIL: an early build failure went unreported: $out"; fail=1; }
exit $fail
