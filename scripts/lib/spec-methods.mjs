// build:release's codegen guard (REA-18): the TurboModule spec's method names that a grep of
// libappmodules.so can check. Only names of 9+ characters are checkable: clang writes shorter
// literals as immediates (seen 2026-10-02 on the Phase 3 build). Prints them space-separated;
// exits 1 when there are none, so a reformatted spec cannot leave the guard checking nothing.
import {readFileSync, realpathSync} from 'node:fs';
import {fileURLToPath} from 'node:url';

export function specMethods(source) {
  const at = source.indexOf('interface Spec');
  if (at < 0) return [];
  return [...source.slice(at).matchAll(/^ {2}(\w{9,})\(/gm)].map(m => m[1]);
}

// Run as a script, not imported. Compared as real paths: a URL-encoded character (a space, %,
// #) or a symlinked script would otherwise skip the CLI and exit 0 with nothing checked.
const isMain = () => {
  try {
    return realpathSync(fileURLToPath(import.meta.url)) === realpathSync(process.argv[1]);
  } catch {
    return false;
  }
};

if (process.argv[1] && isMain()) {
  const file = process.argv[2];
  const names = specMethods(readFileSync(file, 'utf8'));
  if (names.length === 0) {
    console.error(`refused: no method names of 9+ characters found in ${file}'s interface Spec; the build guard would check nothing`);
    process.exit(1);
  }
  console.log(names.join(' '));
}
