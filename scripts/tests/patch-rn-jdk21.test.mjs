// REA-43: the JDK 21 patch of React Native's Gradle plugin, run against a copy of the real files.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { cpSync, existsSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { EDITS, patch } from '../patch-rn-jdk21.mjs';

const PLUGIN = 'node_modules/@react-native/gradle-plugin';
const has = existsSync(join(PLUGIN, 'package.json'));

// A copy of the plugin as npm ships it: the tarball, since postinstall has already patched ours.
function upstream() {
  const root = mkdtempSync(join(tmpdir(), 'jdk21-'));
  const version = JSON.parse(readFileSync(join(PLUGIN, 'package.json'), 'utf8')).version;
  const tgz = execFileSync('npm', ['pack', '--silent', `@react-native/gradle-plugin@${version}`], {
    cwd: root,
    encoding: 'utf8',
  }).trim();
  execFileSync('tar', ['xzf', tgz], { cwd: root });
  cpSync(join(root, 'package'), join(root, PLUGIN), { recursive: true });
  return root;
}

// npm pack needs the registry, so the upstream cases run where it is reachable (CI, this
// machine); the installed tree's case always runs.
let root;
try {
  root = has ? upstream() : null;
} catch {
  root = null;
}

test('the installed plugin is patched (postinstall ran)', { skip: !has && 'no node_modules' }, () => {
  assert.equal(patch('.', { write: false }), 'already');
});

test('patches the upstream plugin, then is a no-op', { skip: !root && 'no upstream copy' }, () => {
  const kt = join(root, PLUGIN, EDITS[4][0]);
  assert.match(readFileSync(kt, 'utf8'), /jvmToolchain\(17\)/);
  assert.equal(patch(root), 'patched');
  const text = readFileSync(kt, 'utf8');
  assert.equal(text.split('jvmToolchain(21)').length - 1, 2);
  assert.equal(text.split('JvmTarget.JVM_17').length - 1, 2);
  for (const [rel] of EDITS.slice(0, 4)) {
    assert.match(readFileSync(join(root, PLUGIN, rel), 'utf8'), /kotlin \{ jvmToolchain\(21\) \}/);
  }
  const before = EDITS.map(([rel]) => readFileSync(join(root, PLUGIN, rel), 'utf8'));
  assert.equal(patch(root), 'already');
  assert.deepEqual(
    EDITS.map(([rel]) => readFileSync(join(root, PLUGIN, rel), 'utf8')),
    before,
  );
});

test('refuses unknown upstream text and changes nothing', { skip: !root && 'no upstream copy' }, () => {
  const fresh = upstream();
  const kts = join(fresh, PLUGIN, EDITS[0][0]);
  writeFileSync(kts, readFileSync(kts, 'utf8').replace('jvmToolchain(17)', 'jvmToolchain(18)'));
  const kt = join(fresh, PLUGIN, EDITS[4][0]);
  const ktBefore = readFileSync(kt, 'utf8');
  assert.throws(() => patch(fresh), /neither upstream nor patched/);
  assert.equal(readFileSync(kt, 'utf8'), ktBefore);
});

test('refuses a JDK 17 toolchain it does not know about', { skip: !root && 'no upstream copy' }, () => {
  const fresh = upstream();
  writeFileSync(join(fresh, PLUGIN, 'shared/extra.gradle.kts'), 'kotlin { jvmToolchain( 17 ) }\n');
  assert.throws(() => patch(fresh), /extra\.gradle\.kts asks for a JDK 17 toolchain/);
});
