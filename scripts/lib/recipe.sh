# The F-Droid recipe's prebuild, run the way fdroidserver runs it (REA-26 review): every entry
# joined with "; " into one bash -c from the build's subdir, with $$hermes$$ replaced by the
# srclib's path. repro-check.sh and its test use it, so the recipe itself is what gets tested.
# PyYAML: the F-Droid venv's, else the system python's (CI).
recipe_py() { if [ -x .venv-fdroid/bin/python ]; then echo .venv-fdroid/bin/python; else echo python3; fi; }
recipe_prebuild() { # recipe_prebuild <repo root> <hermes srclib dir>
  local root=$1 hermes=$2 cmd
  cmd=$($(recipe_py) - fdroid/io.loopstring.readme.yml "$hermes" <<'PY'
import sys, yaml
build = yaml.safe_load(open(sys.argv[1]))["Builds"][-1]
print("; ".join(build.get("prebuild", [])).replace("$$hermes$$", sys.argv[2]))
PY
)
  ( cd "$root/$($(recipe_py) -c "import yaml; print(yaml.safe_load(open('fdroid/io.loopstring.readme.yml'))['Builds'][-1]['subdir'])")" && bash -c "$cmd" )
}
# recipe_local_metadata <recipe> <repo> <commit>: the recipe for fdroid:build (REA-38), building
# <commit> of a local clone. The Binaries/AllowedAPKSigningKeys lines go: the release they name
# is published after this check, not before. Edited as text: a YAML round trip turns the
# gradle: entry "yes" into true, which fdroidserver (YAML 1.2) reads as a flavor named True.
recipe_local_metadata() {
  $(recipe_py) - "$1" "$2" "$3" <<'PY'
import re, sys
lines = open(sys.argv[1]).read().splitlines()
if sum(1 for l in lines if l.startswith("  - versionName:")) != 1:
    sys.exit("recipe_local_metadata: the recipe must list exactly one build")
out = []
for l in lines:
    if re.match(r"(Binaries|AllowedAPKSigningKeys):", l):
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
