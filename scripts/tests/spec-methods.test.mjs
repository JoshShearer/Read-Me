// REA-18: build:release's codegen guard read the spec's method names with a regex and carried
// on when it matched none, so a reformatted spec left it checking only the module name.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {copyFileSync, mkdirSync, mkdtempSync, readFileSync, symlinkSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join, resolve} from 'node:path';
import {specMethods} from '../lib/spec-methods.mjs';

const SPEC_TS = 'src/native/NativeReadMeSpeech.ts';
const run = file => spawnSync('node', ['scripts/lib/spec-methods.mjs', file], {encoding: 'utf8'});

test('the real spec gives its long method names', () => {
  const names = specMethods(readFileSync(SPEC_TS, 'utf8'));
  assert.ok(names.length >= 20, `only ${names.length} names`);
  assert.ok(names.includes('getPlayback'));
  assert.ok(names.every(n => n.length >= 9));
  const r = run(SPEC_TS);
  assert.equal(r.status, 0);
  assert.deepEqual(r.stdout.trim().split(' '), names);
});

test('a spec with no matching names is refused', () => {
  const dir = mkdtempSync(join(tmpdir(), 'spec-'));
  const file = join(dir, 'Spec.ts');
  // Four-space indentation: the regex expects two, so nothing matches.
  writeFileSync(file, 'export interface Spec extends TurboModule {\n    getPlayback(): Promise<void>;\n}\n');
  const r = run(file);
  assert.equal(r.status, 1);
  assert.match(r.stderr, /refused/);
  assert.equal(r.stdout, '');
});

test('build-release.sh stops on that refusal under set -e', () => {
  // A command substitution inside a for list is not checked by set -e; an assignment is.
  const script = readFileSync('scripts/build-release.sh', 'utf8');
  assert.match(script, /^\s*methods=\$\(node scripts\/lib\/spec-methods\.mjs "\$SPEC_TS"\)$/m);
  assert.match(script, /for m in "\$SPEC" \$methods; do/);
  const dir = mkdtempSync(join(tmpdir(), 'spec-'));
  const file = join(dir, 'Spec.ts');
  writeFileSync(file, 'export interface Spec {}\n');
  const r = spawnSync('bash', ['-c', `set -euo pipefail; SPEC_TS=${file}; methods=$(node scripts/lib/spec-methods.mjs "$SPEC_TS"); echo reached`], {encoding: 'utf8'});
  assert.notEqual(r.status, 0);
  assert.doesNotMatch(r.stdout, /reached/);
});

test('the CLI runs from a path with a space and through a symlink', () => {
  // The CLI guard compared import.meta.url with file://argv[1]: a space (URL-encoded as %20) or
  // a symlinked script made them differ, so it printed nothing and exited 0, refusal included.
  const dir = mkdtempSync(join(tmpdir(), 'spec methods '));
  mkdirSync(join(dir, 'real dir'));
  const script = join(dir, 'real dir', 'spec-methods.mjs');
  copyFileSync('scripts/lib/spec-methods.mjs', script);
  const link = join(dir, 'linked spec-methods.mjs');
  symlinkSync(script, link);
  const empty = join(dir, 'Empty Spec.ts');
  writeFileSync(empty, 'export interface Spec {}\n');
  const names = specMethods(readFileSync(SPEC_TS, 'utf8'));
  for (const path of [script, link]) {
    const ok = spawnSync('node', [path, resolve(SPEC_TS)], {encoding: 'utf8'});
    assert.equal(ok.status, 0, path);
    assert.deepEqual(ok.stdout.trim().split(' '), names, path);
    const refused = spawnSync('node', [path, empty], {encoding: 'utf8'});
    assert.equal(refused.status, 1, path);
    assert.match(refused.stderr, /refused/, path);
  }
});
