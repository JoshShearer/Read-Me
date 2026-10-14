// build:release's codegen guard (REA-18): the TurboModule spec's method names that a grep of
// libappmodules.so can check. Only names of 9+ characters are checkable: clang writes shorter
// literals as immediates (seen 2026-10-02 on the Phase 3 build). Prints them space-separated;
// exits 1 when there are none, so a reformatted spec cannot leave the guard checking nothing.
import {readFileSync} from 'node:fs';

export function specMethods(source) {
  const at = source.indexOf('interface Spec');
  if (at < 0) return [];
  return [...source.slice(at).matchAll(/^ {2}(\w{9,})\(/gm)].map(m => m[1]);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const file = process.argv[2];
  const names = specMethods(readFileSync(file, 'utf8'));
  if (names.length === 0) {
    console.error(`refused: no method names of 9+ characters found in ${file}'s interface Spec; the build guard would check nothing`);
    process.exit(1);
  }
  console.log(names.join(' '));
}
