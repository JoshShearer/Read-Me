// Tests for scripts/run-tickets/ticket-ops.mjs, the mechanical half of /run-tickets, against a
// throwaway git repo with a local bare "origin". Nothing here reaches GitHub, Linear or the phone:
// every case is decided before the script would call gh.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const OPS = path.resolve(
  path.dirname(new URL(import.meta.url).pathname),
  '../run-tickets/ticket-ops.mjs',
);

function sandbox() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ticket-ops-'));
  const g = (cwd, ...a) =>
    execFileSync('git', a, { cwd, encoding: 'utf8', stdio: 'pipe' }).trim();
  const origin = path.join(dir, 'origin.git');
  const lane = path.join(dir, 'Read-Me-run-20261008-140000');
  g(dir, 'init', '-q', '--bare', '-b', 'main', origin);
  g(dir, 'clone', '-q', origin, lane);
  for (const [k, v] of [
    ['user.email', 't@t'],
    ['user.name', 't'],
  ])
    g(lane, 'config', k, v);
  fs.writeFileSync(path.join(lane, 'a.txt'), 'a\n');
  g(lane, 'add', '-A');
  g(lane, 'commit', '-q', '-m', 'init');
  g(lane, 'push', '-q', 'origin', 'HEAD:main');
  g(lane, 'checkout', '-q', '-b', 'run/20261008-140000');
  const state = path.join(dir, 'pipeline-state.20261008-140000.json');
  const ops = (args, input) => {
    let file;
    if (input !== undefined) {
      file = path.join(dir, `ret-${Math.random()}.json`);
      fs.writeFileSync(
        file,
        typeof input === 'string' ? input : JSON.stringify(input),
      );
    }
    const r = spawnSync(
      'node',
      [OPS, '--state', state, ...args, ...(file ? ['--file', file] : [])],
      { cwd: lane, encoding: 'utf8' },
    );
    return {
      code: r.status,
      out: JSON.parse(r.stdout.trim().split('\n').pop() || '{}'),
    };
  };
  const init = args =>
    ops([
      'init',
      '--args',
      args,
      '--stamp',
      '20261008-140000',
      '--primary',
      dir,
      '--worktree',
      lane,
      '--run-branch',
      'run/20261008-140000',
    ]);
  const read = () => JSON.parse(fs.readFileSync(state, 'utf8'));
  const patch = (key, fields, top = {}) => {
    const s = read();
    Object.assign(
      s.tickets.find(t => t.id === key),
      fields,
    );
    Object.assign(s, top);
    fs.writeFileSync(state, JSON.stringify(s));
  };
  return {
    dir,
    lane,
    state,
    ops,
    init,
    read,
    patch,
    g: (...a) => g(lane, ...a),
  };
}
const SHA = c => c.repeat(40);

test('init derives --no-merge from the arguments and normalises keys', () => {
  const s = sandbox();
  const r = s.init('REA-3, 4 --no-merge');
  assert.equal(r.code, 0);
  assert.equal(s.read().noMerge, true);
  assert.deepEqual(
    s.read().tickets.map(t => t.id),
    ['REA-3', 'REA-4'],
  );
  const t = sandbox();
  assert.equal(t.init('REA-3').code, 0);
  assert.equal(t.read().noMerge, false);
});

test('init refuses a key that is not a Read Me key, and a stamp collision', () => {
  const s = sandbox();
  assert.equal(s.init('REA-1;rm -rf x').code, 1);
  assert.equal(fs.existsSync(s.state), false);
  assert.equal(s.init('NRL-12').code, 1);
  assert.equal(s.init('REA-1').code, 0);
  const again = s.init('REA-2');
  assert.equal(again.code, 2);
  assert.deepEqual(
    s.read().tickets.map(t => t.id),
    ['REA-1'],
  );
});

test('record refuses malformed or mistyped returns and writes nothing', () => {
  const s = sandbox();
  s.init('REA-1');
  s.patch('REA-1', { phase: 'build', status: 'in_progress' });
  const before = fs.readFileSync(s.state, 'utf8');
  for (const bad of [
    'not json',
    '[1]',
    { result: 'shipped' },
    { result: 'done', planNote: 3 },
    { result: 'done', clarificationAdd: 'x' },
    { result: 'done', gatedSha: 'abc' },
  ]) {
    assert.equal(
      s.ops(['record', 'REA-1', '--phase', 'build'], bad).code,
      1,
      JSON.stringify(bad),
    );
  }
  assert.equal(fs.readFileSync(s.state, 'utf8'), before);
});

test('record applies only onto a ticket in that phase, and routes it', () => {
  const s = sandbox();
  s.init('REA-1');
  assert.equal(
    s.ops(['record', 'REA-1', '--phase', 'build'], { result: 'done' }).code,
    1,
  );
  s.patch('REA-1', { phase: 'build', status: 'in_progress' });
  const r = s.ops(['record', 'REA-1', '--phase', 'build'], {
    result: 'done',
    planNote: 'p',
    commitSha: SHA('f'),
  });
  assert.equal(r.code, 0);
  assert.equal(r.out.phase, 'ship');
  assert.deepEqual(r.out.dropped, ['commitSha']);
  assert.equal(s.read().tickets[0].commitSha, null);
});

test('a verdict must name the current commitSha, and Verify must pin its base', () => {
  const s = sandbox();
  s.init('REA-1');
  s.patch('REA-1', {
    phase: 'critic',
    status: 'in_progress',
    commitSha: SHA('a'),
  });
  const critic = v => s.ops(['record', 'REA-1', '--phase', 'critic'], v);
  assert.equal(
    critic({ result: 'pass', criticVerdict: 'pass', criticSha: SHA('b') }).code,
    1,
  );
  assert.equal(
    critic({ result: 'pass', criticVerdict: 'block', criticSha: SHA('a') })
      .code,
    1,
  );
  const blocked = critic({
    result: 'block',
    criticVerdict: 'block',
    criticSha: SHA('a'),
    verifyFindings: 'x',
  });
  assert.equal(blocked.out.phase, 'fix');
  s.patch('REA-1', { phase: 'verify' });
  const verify = v => s.ops(['record', 'REA-1', '--phase', 'verify'], v);
  assert.equal(
    verify({ result: 'pass', verifyVerdict: 'pass', verifiedSha: SHA('a') })
      .code,
    1,
  );
  const ok = verify({
    result: 'pass',
    verifyVerdict: 'pass',
    verifiedSha: SHA('a'),
    verifiedBaseSha: SHA('c'),
  });
  assert.equal(ok.out.phase, 'merge');
});

test('notOwned only goes false -> true; done needs a merge commit; done is closed', () => {
  const s = sandbox();
  s.init('REA-1');
  const orch = v => s.ops(['record', 'REA-1', '--phase', 'orchestrator'], v);
  assert.equal(orch({ notOwned: true }).code, 0);
  assert.equal(orch({ notOwned: false }).code, 1);
  assert.equal(orch({ status: 'done' }).code, 1);
  s.patch('REA-1', { mergeCommit: SHA('d'), phase: 'finish' });
  assert.equal(orch({ status: 'done' }).code, 0);
  assert.equal(orch({ fixRound: 2 }).code, 1);
  assert.equal(
    orch({ trackerWriteAdd: { op: 'state:Done', ok: true } }).code,
    0,
  );
  assert.equal(s.read().tickets[0].trackerWrites.length, 1);
});

test('merge refuses under --no-merge, without verdicts, and when the base moved', () => {
  const s = sandbox();
  s.init('REA-1 --no-merge');
  s.patch('REA-1', { phase: 'merge', status: 'in_progress' });
  assert.equal(s.ops(['merge', 'REA-1']).code, 3);

  const t = sandbox();
  t.init('REA-1');
  const base = t.g('rev-parse', 'origin/main');
  t.patch('REA-1', {
    phase: 'merge',
    status: 'in_progress',
    commitSha: SHA('a'),
    criticVerdict: 'pass',
    criticSha: SHA('a'),
    verifyVerdict: null,
  });
  assert.equal(t.ops(['merge', 'REA-1']).code, 2);
  t.patch('REA-1', {
    verifyVerdict: 'pass',
    verifiedSha: SHA('a'),
    verifiedBaseSha: base,
  });
  t.g('checkout', '-q', '-b', 'other', 'origin/main');
  fs.writeFileSync(path.join(t.lane, 'b.txt'), 'b\n');
  t.g('add', '-A');
  t.g('commit', '-q', '-m', 'another run merged');
  t.g('push', '-q', 'origin', 'HEAD:main');
  const r = t.ops(['merge', 'REA-1']);
  assert.equal(r.code, 4);
  assert.equal(t.read().tickets[0].phase, 'fix');
  assert.match(t.read().tickets[0].verifyFindings, /moved/);
});

test('finish refuses a ticket with no recorded merge commit', () => {
  const s = sandbox();
  s.init('REA-1');
  s.patch('REA-1', { phase: 'finish', status: 'in_progress' });
  assert.equal(
    s.ops(['finish', 'REA-1', '--run-branch', 'run/20261008-140000']).code,
    1,
  );
});

test('branch creates the conventional branch off the run branch, and blocks a collision', () => {
  const s = sandbox();
  s.init('REA-7');
  const args = [
    'branch',
    'REA-7',
    '--run-branch',
    'run/20261008-140000',
    '--title',
    "Fetcher: don't follow 6 redirects",
    '--type',
    'bug',
  ];
  const r = s.ops(args);
  assert.equal(r.code, 0, JSON.stringify(r.out));
  assert.equal(r.out.branch, 'fix/rea-7-fetcher-don-t-follow-6-redirects');
  assert.equal(s.g('branch', '--show-current'), r.out.branch);
  assert.equal(s.read().tickets[0].phase, 'build');

  const t = sandbox();
  t.init('REA-7');
  t.g('branch', 'fix/rea-7-fetcher-don-t-follow-6-redirects');
  assert.equal(t.ops(args).code, 3);
  assert.equal(t.read().tickets[0].status, 'blocked');
});

test('risk puts a gate-defining change at the deepest level; device-check refuses a guard change', () => {
  const s = sandbox();
  s.init('REA-1');
  s.g('checkout', '-q', '-b', 'fix/rea-1-x');
  fs.mkdirSync(path.join(s.lane, 'scripts/lib'), { recursive: true });
  fs.writeFileSync(path.join(s.lane, 'jest.config.js'), 'module.exports={}\n');
  fs.writeFileSync(path.join(s.lane, 'scripts/lib/device.sh'), '# weaker\n');
  s.g('add', '-A');
  s.g('commit', '-q', '-m', 'weaken');
  const head = s.g('rev-parse', 'HEAD');
  s.patch('REA-1', { commitSha: head, branch: 'fix/rea-1-x' });
  const r = s.ops(['risk', 'REA-1']);
  assert.equal(r.out.depth, 'L2+Double');
  assert.equal(r.out.gatesChanged, true);
  const d = s.ops(['device-check', 'REA-1']);
  assert.equal(d.out.allowed, false);
  assert.match(d.out.line, /device\.sh/);
});

test('set-run writes only the run-level fields it knows, with their types', () => {
  const s = sandbox();
  s.init('REA-1');
  const f = path.join(s.dir, 'run.json');
  const setRun = obj => {
    fs.writeFileSync(f, JSON.stringify(obj));
    return s.ops(['set-run', '--file', f]).code;
  };
  assert.equal(setRun({ halted: 'REA-1', gatesAtStart: 'all pass' }), 0);
  assert.equal(s.read().halted, 'REA-1');
  assert.equal(setRun({ noMerge: false }), 1);
  assert.equal(setRun({ halted: 'rm -rf /' }), 1);
  assert.equal(s.read().noMerge, false);
  assert.equal(setRun({ halted: null, worktreeRemoved: true }), 0);
});
