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
# <commit> of a local clone. Only the last build is kept, and the Binaries/AllowedAPKSigningKeys
# lines go: the release they name is published after this check, not before.
recipe_local_metadata() {
  $(recipe_py) - "$1" "$2" "$3" <<'PY'
import sys, yaml
m = yaml.safe_load(open(sys.argv[1]))
m["Repo"] = sys.argv[2]
m["Builds"] = m["Builds"][-1:]
m["Builds"][0]["commit"] = sys.argv[3]
for k in ("Binaries", "AllowedAPKSigningKeys"):
    m.pop(k, None)
print(yaml.safe_dump(m, sort_keys=False, width=1000), end="")
PY
}
# apk_same <a> <b>: equal apart from signatures (apksigcopier copies a's signature onto b and
# verifies). apk_differs lists the entries that are not.
apk_same() { PATH="${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools/37.0.0:$PATH" .venv-fdroid/bin/apksigcopier compare "$1" "$2" >/dev/null 2>&1; }
apk_differs() {
  diff <(unzip -Z1 "$1" | sort) <(unzip -Z1 "$2" | sort) || true
  for f in $(unzip -Z1 "$1" | grep -v '^META-INF/'); do
    cmp -s <(unzip -p "$1" "$f") <(unzip -p "$2" "$f") || echo "  differs: $f"
  done
}
