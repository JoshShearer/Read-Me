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
grep -qx 'reactNativeDevServerIp=localhost' "$T/repo/android/gradle.properties" \
  && echo "ok: the last existing property is intact" || { echo "FAIL: reactNativeDevServerIp corrupted"; fail=1; }
# The recipe's Hermes srclib tag must be the hermesc npm installs (critique A F2): an upgrade
# that forgets the recipe would build the bundle with the old compiler.
want="hermes-v$(node -p "require('./node_modules/hermes-compiler/package.json').version")"
got=$($(recipe_py) -c "import yaml; print(yaml.safe_load(open('fdroid/io.loopstring.readme.yml'))['Builds'][-1]['srclibs'][0].split('@',1)[1])")
[ "$got" = "$want" ] && echo "ok: the recipe's Hermes tag is $want" || { echo "FAIL: recipe pins $got, npm has $want"; fail=1; }
# build-hermesc.sh refuses a source checkout at another tag.
mkdir -p "$T/wrongsrc" && git -C "$T/wrongsrc" init -q && git -C "$T/wrongsrc" -c user.email=t@t -c user.name=t commit -q --allow-empty -m x && git -C "$T/wrongsrc" tag hermes-v0.0.1
out=$(HERMES_SRC="$T/wrongsrc" bash scripts/build-hermesc.sh "$T/wrongout" 2>&1 || true)
if grep -q "refused: .* is at hermes-v0.0.1" <<<"$out"; then echo "ok: build-hermesc refuses a checkout at another tag"
else echo "FAIL: build-hermesc did not refuse the checkout's tag"; fail=1; fi
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
assert m["Builds"][-1]["commit"] == "0123456789abcdef0123456789abcdef01234567"
assert "Binaries" not in m and "AllowedAPKSigningKeys" not in m
assert len(m["Builds"]) == 1
PY
grep -q '^Binaries:' fdroid/io.loopstring.readme.yml && grep -q '^AllowedAPKSigningKeys:' fdroid/io.loopstring.readme.yml \
  && echo "ok: the repo's recipe names our signed APK" || { echo "FAIL: the recipe lacks Binaries or AllowedAPKSigningKeys"; fail=1; }
v=$(node -p "require('./package.json').version")
[ "$($(recipe_py) -c "import yaml; print(yaml.safe_load(open('fdroid/io.loopstring.readme.yml'))['Builds'][-1]['versionName'])")" = "$v" ] \
  && echo "ok: the recipe builds version $v" || { echo "FAIL: the recipe's versionName is not package.json's $v"; fail=1; }
exit $fail
