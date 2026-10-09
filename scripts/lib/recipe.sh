# The F-Droid recipe's prebuild, run the way fdroidserver runs it (REA-26 review): every entry
# joined with "; " into one bash -c from the build's subdir. One ABI's build entry (REA-40): the
# one whose binary: is that ABI's APK. repro-check.sh and its test use it, so the recipe itself
# is what gets tested.
# PyYAML: the F-Droid venv's, else the system python's (CI).
recipe_py() { if [ -x .venv-fdroid/bin/python ]; then echo .venv-fdroid/bin/python; else echo python3; fi; }
recipe_prebuild() { # recipe_prebuild <repo root> <abi>
  local root=$1 abi=$2 out subdir cmd
  out=$($(recipe_py) - fdroid/io.loopstring.readme.yml "$abi" <<'PY'
import sys, yaml
builds = [b for b in yaml.safe_load(open(sys.argv[1]))["Builds"] if b["binary"].endswith("-%s.apk" % sys.argv[2])]
if len(builds) != 1:
    sys.exit("recipe_prebuild: %d build entries for %s" % (len(builds), sys.argv[2]))
print(builds[0]["subdir"])
print("; ".join(builds[0].get("prebuild", [])))
PY
) || return 1
  subdir=$(sed -n 1p <<<"$out"); cmd=$(sed -n '2,$p' <<<"$out")
  # fdroidserver runs it with -e -u -o pipefail: a failing entry stops the build there too.
  ( cd "$root/$subdir" && bash -e -u -o pipefail -c "$cmd" )
}
# recipe_local_metadata <recipe> <repo> <commit>: the recipe for fdroid:build (REA-38), building
# <commit> of a local clone. The Binaries/binary:/AllowedAPKSigningKeys lines go: the release
# they name is published after this check, not before. Edited as text: a YAML round trip turns
# the gradle: entry "yes" into true, which fdroidserver (YAML 1.2) reads as a flavor named True.
recipe_local_metadata() {
  $(recipe_py) - "$1" "$2" "$3" <<'PY'
import re, sys
lines = open(sys.argv[1]).read().splitlines()
names = {l.split(":", 1)[1].strip() for l in lines if l.startswith("  - versionName:")}
if len(names) != 1:
    sys.exit("recipe_local_metadata: the recipe must build exactly one version, not %s" % sorted(names))
out = []
for l in lines:
    if re.match(r"(Binaries|AllowedAPKSigningKeys):", l) or l.startswith("    binary:"):
        continue
    if l.startswith("Repo:"):
        l = "Repo: " + sys.argv[2]
    elif l.startswith("    commit:"):
        l = "    commit: " + sys.argv[3]
    out.append(l)
print("\n".join(out))
PY
}
# apk_same <a> <b>: equal apart from signatures (apksigcopier copies a's signature onto b and
# verifies). apk_differs lists the entries that are not.
apk_same() { PATH="${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools/37.0.0:$PATH" .venv-fdroid/bin/apksigcopier compare "$@" >/dev/null 2>&1; }
# apk_same_unsigned <signed> <unsigned>: F-Droid's build output is unsigned (REA-38).
apk_same_unsigned() { apk_same --unsigned "$1" "$2"; }
apk_differs() {
  diff <(unzip -Z1 "$1" | sort) <(unzip -Z1 "$2" | sort) || true
  for f in $(unzip -Z1 "$1" | grep -v '^META-INF/'); do
    cmp -s <(unzip -p "$1" "$f") <(unzip -p "$2" "$f") || echo "  differs: $f"
  done
}
# fdroid_build_failed <log>: report F-Droid's failed build and exit 1. Falls back to the log's
# tail when no ERROR line exists (apt, a clone or a checksum fails before fdroid logs one);
# under pipefail a grep that finds nothing would otherwise end the script without a word.
fdroid_build_failed() {
  { grep -E "ERROR|What went wrong" -A3 "$1" || tail -20 "$1"; } | tail -20 || true
  echo "fdroid:build: F-Droid's build FAILED (log: $1)"
  exit 1
}
