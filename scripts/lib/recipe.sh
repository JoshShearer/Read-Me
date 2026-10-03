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
