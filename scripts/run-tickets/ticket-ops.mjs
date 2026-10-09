#!/usr/bin/env node
// The mechanical half of /run-tickets (.claude/commands/run-tickets.md): branching, shipping,
// the merge preflight, finishing, and every write to the run's state file. These steps make no
// judgement calls, so they are a script the orchestrator runs with one Bash call, not an agent
// that pays for AGENTS.md on every tool call and can improvise.
//
// Linear is reached only through the linear-rea MCP server, which a script cannot call. Tracker
// reads and writes therefore stay with the orchestrator; it records each write here
// (`record --phase orchestrator` with `trackerWriteAdd`) so the end-of-run report can name every
// write that did not land.
//
// The orchestrator runs a FROZEN copy of this file, taken from origin/main before any ticket
// branch is checked out: a ticket that edits it must not change the gate that merges it.
//
//   node <ops>/ticket-ops.mjs --state <file> <cmd> [KEY] [options]
//
//   init --args '<$ARGUMENTS>' --stamp <s> --primary <p> --worktree <w> --run-branch <b>
//                         create the state file exclusively; flags and keys come from the args
//   add <KEY>             register a pending ticket (idempotent)
//   show <KEY> [f,...]    print the ticket entry, or a subset of it
//   record <KEY> --phase <p> --file <json>   validate an agent's return and apply it
//   branch <KEY> --run-branch <b> --title <t> --type <bug|feature|spike|docs>
//   ship <KEY>            gates unless already gated, leased push, read back, PR create or adopt
//   risk <KEY>            critic depth for origin/main...commitSha
//   pr-append <KEY> --file <txt>             append to the PR body, read it back
//   merge <KEY>           preflight; prints the `gh pr merge` the ORCHESTRATOR runs as plain Bash
//   merged <KEY>          read the merge back; prints the remote-branch delete to run
//   finish <KEY> --run-branch <b>            resync the lane, delete the local branch by equality
//   device-check <KEY>    may this ticket's head drive the phone at all
//   set-run --file <json>  run-level fields: halted, worktreeRemoved, gatesAtStart
//   guard [--snapshot]    no tag or GitHub release appeared since the run started
//
// Output: one JSON line. Exit: 0 ok, 1 usage or malformed input (nothing written; re-run the
// agent once, then block), 2 infrastructure (stop the run), 3 blocked (status and blockedReason
// set), 4 fail (route to Fix; verifyFindings set).
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const EXIT = { ok: 0, usage: 1, error: 2, blocked: 3, fail: 4 };
const BASE = 'main';
const KEY_RE = /^REA-[1-9][0-9]{0,5}$/;
const BRANCH_RE =
  /^(feature|fix|spike|docs)\/rea-[1-9][0-9]*-[a-z0-9]+(-[a-z0-9]+)*$/;
const SHA_RE = /^[0-9a-f]{40}$/;
const VERDICTS = [
  'criticVerdict',
  'criticSha',
  'verifyVerdict',
  'verifiedSha',
  'verifiedBaseSha',
];

// Files that define what the gates DO. A diff touching one is graded by the gate it changes, so it
// never takes the "already gated" shortcut and the critic reviews it at the deepest level.
const GATE_FILES =
  /^(package\.json|package-lock\.json|tsconfig\.json|\.eslintrc\.js|\.eslintignore|\.prettierrc\.js|jest\.config\.js|babel\.config\.js|\.github\/|scripts\/tests\/|scripts\/(check-licenses|license-expr|make-notices|make-devcheck-fixtures)\.mjs|scripts\/lib\/|android\/(build\.gradle|settings\.gradle|gradle\.properties|gradle\/|app\/build\.gradle|app\/proguard-rules\.pro|app\/src\/test\/))/;
// The pipeline itself, and the device guard it relies on.
const PIPELINE_FILES =
  /^(scripts\/run-tickets\/|\.claude\/commands\/run-tickets\.md$|scripts\/lib\/device\.sh$|opencode\.json$)/;
// What a ticket may never change unattended: the release signer every release is checked against.
const OWNER_ONLY_FILES = /^release\/signing-cert\.sha256$/;

// What each phase may set, with its type. `?` = may also be null.
const T = {
  s: 'string',
  's?': 'string?',
  b: 'boolean',
  n: 'number',
  a: 'array',
  o: 'object',
  'o?': 'object?',
};
const COMMON = { handoff: 's?', blockedReason: 's?', clarificationAdd: 'a' };
const FIELDS = {
  triage: { type: 's', needsDevice: 'b', requirement: 's?', ...COMMON },
  build: {
    planNote: 's',
    reproduction: 's?',
    reproConfirmed: 'b',
    implementationSummary: 's',
    gatedSha: 's',
    prTitle: 's',
    prBody: 's',
    ...COMMON,
  },
  fix: {
    gatedSha: 's',
    verifyFindings: 's?',
    implementationSummary: 's',
    roundNote: 's',
    branch: 's',
    prNumber: 'n?',
    ...COMMON,
  },
  critic: {
    criticVerdict: 's',
    criticSha: 's',
    verifyFindings: 's?',
    blockedReason: 's?',
  },
  verify: {
    verifyVerdict: 's',
    verifiedSha: 's',
    verifiedBaseSha: 's',
    verifyNotes: 's',
    verifyFindings: 's?',
    gates: 's',
    device: 's',
    ciStatus: 's',
    ...COMMON,
  },
  orchestrator: {
    fixRound: 'n',
    status: 's',
    blockedReason: 's?',
    notOwned: 'b',
    phase: 's',
    handoff: 's?',
    trackerWriteAdd: 'o',
    deviceNote: 's',
  },
};
const RESULTS = {
  triage: ['done', 'blocked'],
  build: ['done', 'handoff', 'blocked'],
  fix: ['done', 'handoff', 'blocked'],
  critic: ['pass', 'concerns', 'block'],
  verify: ['pass', 'fail', 'handoff', 'blocked'],
  orchestrator: [undefined],
};
// Routing: phase -> result -> next phase. `handoff` stays; `blocked` blocks.
const NEXT = {
  triage: { done: 'start' },
  build: { done: 'ship' },
  fix: { done: 'ship' },
  critic: { pass: 'verify', concerns: 'verify', block: 'fix' },
  verify: { pass: 'merge', fail: 'fix' },
};

class Stop extends Error {
  constructor(kind, message) {
    super(message);
    this.kind = kind;
  }
}

const argv = process.argv.slice(2);
const opt = name => {
  const i = argv.indexOf(`--${name}`);
  if (i === -1) return undefined;
  const v = argv[i + 1];
  argv.splice(i, 2);
  return v;
};
const flag = name => {
  const i = argv.indexOf(`--${name}`);
  if (i === -1) return false;
  argv.splice(i, 1);
  return true;
};
const STATE = opt('state');
const PHASE = opt('phase');
const RUN_BRANCH = opt('run-branch');
const FILE = opt('file');
const SNAPSHOT = flag('snapshot');
const INIT = {
  args: opt('args'),
  stamp: opt('stamp'),
  primary: opt('primary'),
  worktree: opt('worktree'),
};
const TITLE = opt('title');
const TYPE = opt('type');
const [cmd, KEY, ...REST] = argv;

function sh(bin, args, { input } = {}) {
  return execFileSync(bin, args, {
    encoding: 'utf8',
    input,
    stdio: ['pipe', 'pipe', 'pipe'],
  }).trim();
}
function run(bin, args) {
  const r = spawnSync(bin, args, {
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
  });
  const out = `${r.stdout ?? ''}${r.stderr ?? ''}`;
  return {
    code: r.status ?? 1,
    stdout: r.stdout ?? '',
    out: out.length > 4000 ? `...${out.slice(-4000)}` : out,
  };
}
const git = (...a) => sh('git', a);
const now = () => new Date().toISOString().replace(/\.\d{3}Z$/, 'Z');

// ------------------------------------------------------------------------------ state file

function loadState() {
  if (!STATE) throw new Stop('usage', '--state <file> is required');
  return JSON.parse(fs.readFileSync(STATE, 'utf8'));
}
function saveState(state) {
  const tmp = `${STATE}.tmp-${process.pid}`;
  fs.writeFileSync(tmp, `${JSON.stringify(state, null, 2)}\n`);
  fs.renameSync(tmp, STATE);
}
function ticketOf(state, key) {
  const t = state.tickets.find(x => x.id === key);
  if (!t) throw new Stop('usage', `${key} is not in ${STATE}`);
  return t;
}
function apply(t, fields, phase, result) {
  const shaChanged = 'commitSha' in fields && fields.commitSha !== t.commitSha;
  Object.assign(t, fields);
  if (shaChanged) for (const f of VERDICTS) if (!(f in fields)) t[f] = null;
  t.history.push({
    phase: phase ?? t.phase,
    at: now(),
    result: result ?? 'recorded',
  });
}
// Saves even when fn throws, so what happened before a block is still on record.
function mutate(key, fn) {
  const state = loadState();
  const t = ticketOf(state, key);
  try {
    return fn(t, state);
  } finally {
    state.heartbeat = now();
    saveState(state);
  }
}
const newTicket = id => ({
  id,
  status: 'pending',
  phase: 'start',
  type: null,
  title: null,
  branch: null,
  descriptionSnapshot: null,
  clarification: [],
  planNote: null,
  reproduction: null,
  reproConfirmed: false,
  implementationSummary: null,
  gatedSha: null,
  prTitle: null,
  prBody: null,
  roundNote: null,
  prNumber: null,
  prUrl: null,
  commitSha: null,
  mergeCommit: null,
  criticVerdict: null,
  criticSha: null,
  verifyVerdict: null,
  verifiedSha: null,
  verifiedBaseSha: null,
  verifyNotes: null,
  gates: null,
  device: null,
  ciStatus: null,
  fixRound: 0,
  verifyFindings: null,
  handoff: null,
  notOwned: false,
  trackerWrites: [],
  blockedReason: null,
  history: [],
});
function normaliseKey(raw) {
  const k = /^[0-9]+$/.test(raw ?? '')
    ? `REA-${raw}`
    : String(raw ?? '').toUpperCase();
  if (!KEY_RE.test(k))
    throw new Stop('usage', `not a Read Me ticket key: ${JSON.stringify(raw)}`);
  return k;
}

// Every field the agent returned must have the type its phase allows, before anything is written.
function validate(phase, input) {
  if (!input || typeof input !== 'object' || Array.isArray(input))
    throw new Stop('usage', 'agent return is not a JSON object');
  const { result, note, ...rest } = input;
  if (!RESULTS[phase].includes(result))
    throw new Stop(
      'usage',
      `result ${JSON.stringify(result)} is not one of ${RESULTS[phase].join('|') || '(none)'} for ${phase}`,
    );
  if (note !== undefined && typeof note !== 'string')
    throw new Stop('usage', 'note is not a string');
  const allowed = FIELDS[phase];
  const fields = {};
  const dropped = [];
  for (const [k, v] of Object.entries(rest)) {
    const want = allowed[k];
    if (!want) {
      dropped.push(k);
      continue;
    }
    const base = T[want].replace('?', '');
    const ok =
      (v === null && T[want].endsWith('?')) ||
      (base === 'array'
        ? Array.isArray(v)
        : base === 'object'
          ? v !== null && typeof v === 'object' && !Array.isArray(v)
          : typeof v === base);
    if (!ok)
      throw new Stop(
        'usage',
        `${k} must be ${T[want]}, got ${Array.isArray(v) ? 'array' : v === null ? 'null' : typeof v}`,
      );
    fields[k] = v;
  }
  for (const c of fields.clarificationAdd ?? [])
    if (
      !c ||
      typeof c.question !== 'string' ||
      typeof c.answer !== 'string' ||
      typeof c.reason !== 'string'
    )
      throw new Stop(
        'usage',
        'each clarificationAdd needs string question, answer and reason',
      );
  if (
    fields.trackerWriteAdd &&
    (typeof fields.trackerWriteAdd.op !== 'string' ||
      typeof fields.trackerWriteAdd.ok !== 'boolean')
  )
    throw new Stop(
      'usage',
      'trackerWriteAdd needs a string op and a boolean ok',
    );
  for (const k of ['gatedSha', 'criticSha', 'verifiedSha', 'verifiedBaseSha'])
    if (k in fields && !SHA_RE.test(fields[k]))
      throw new Stop('usage', `${k} is not a full commit sha`);
  return { result, note, fields, dropped };
}

// ------------------------------------------------------------------------------ git / gh

const changedFiles = (to = 'HEAD') =>
  git('diff', '--name-only', `origin/${BASE}...${to}`)
    .split('\n')
    .filter(Boolean);
const remoteHead = branch => {
  const line = git('ls-remote', 'origin', `refs/heads/${branch}`);
  return line ? line.split(/\s+/)[0] : null;
};
const prView = (pr, fields) =>
  JSON.parse(sh('gh', ['pr', 'view', String(pr), '--json', fields]));
const tmpFile = text => {
  const f = path.join(
    fs.mkdtempSync(path.join(os.tmpdir(), 'run-tickets-')),
    'body.md',
  );
  fs.writeFileSync(f, text);
  return f;
};
// `gh pr edit --body` has exited 0 and left the body unchanged here, so PATCH and read back.
function appendToPrBody(pr, text) {
  const body = prView(pr, 'body').body;
  if (body.includes(text)) return true;
  sh('gh', [
    'api',
    '-X',
    'PATCH',
    `repos/{owner}/{repo}/pulls/${pr}`,
    '-F',
    `body=@${tmpFile(`${body}\n\n${text}\n`)}`,
  ]);
  return prView(pr, 'body').body.includes(text);
}

// The fast half of CI's js job. Gradle and the release build belong to Verify.
function runGates(files) {
  const steps = [
    ['npm', ['run', 'typecheck']],
    ['npm', ['run', 'lint']],
    ['npm', ['test', '--', '--silent']],
    ['npm', ['run', 'test:scripts']],
    ['node', ['scripts/check-licenses.mjs']],
  ];
  if (files.some(f => f.startsWith('android/')))
    steps.push([
      'sh',
      ['-c', 'cd android && ./gradlew --no-daemon -q testDebugUnitTest'],
    ]);
  for (const [bin, args] of steps) {
    const r = run(bin, args);
    if (r.code !== 0)
      return { ok: false, gate: `${bin} ${args.join(' ')}`, output: r.out };
  }
  return { ok: true };
}

function riskOf(files) {
  const has = re => files.some(f => re.test(f));
  const factors = [];
  if (has(GATE_FILES)) factors.push(['changes the gates that grade it', 4]);
  if (has(PIPELINE_FILES)) factors.push(['pipeline machinery', 2]);
  if (
    has(
      /^android\/app\/src\/main\/.*(Fetcher|Bridge|Share|Playback|Tts)|^src\/native\//,
    )
  )
    factors.push(['network, bridge or playback surface', 2]);
  if (has(/^(package(-lock)?\.json|android\/.*\.gradle)$/))
    factors.push(['dependencies', 1]);
  if (
    has(/\.(ts|tsx|kt|mjs|sh)$/) &&
    !has(/(__tests__|\/test\/|scripts\/tests\/|\.test\.)/)
  )
    factors.push(['no test changes', 1]);
  if (has(/^(srs\.md|docs\/adr\/)/)) factors.push(['spec or ADR', 1]);
  const score = factors.reduce((n, [, w]) => n + w, 0);
  const floor = has(/^\.claude\//) ? 2 : 0;
  return {
    score,
    depth: score >= 4 ? 'L2+Double' : Math.max(score, floor) >= 2 ? 'L2' : 'L1',
    factors: factors.map(f => f[0]),
    gatesChanged: has(GATE_FILES),
    files: files.length,
  };
}

function publishSnapshot() {
  const tags = run('git', ['ls-remote', '--tags', 'origin']);
  const rel = run('gh', [
    'release',
    'list',
    '--limit',
    '200',
    '--json',
    'tagName,isDraft,isPrerelease',
  ]);
  if (tags.code !== 0 || rel.code !== 0)
    throw new Stop(
      'error',
      `cannot read tags or releases: ${tags.out}${rel.out}`,
    );
  return {
    tags: tags.stdout.split('\n').filter(Boolean).sort(),
    releases: JSON.parse(rel.stdout)
      .map(r => `${r.tagName} draft=${r.isDraft} pre=${r.isPrerelease}`)
      .sort(),
  };
}

// ------------------------------------------------------------------------------ commands

const commands = {
  init() {
    if (!STATE) throw new Stop('usage', '--state is required');
    for (const k of ['args', 'stamp', 'primary', 'worktree'])
      if (!INIT[k] && !(k === 'args' && INIT.args === ''))
        throw new Stop('usage', `--${k} is required`);
    if (!RUN_BRANCH) throw new Stop('usage', '--run-branch is required');
    const words = INIT.args.split(/[\s,]+/).filter(Boolean);
    const flags = new Set(words.filter(w => w.startsWith('--')));
    const known = ['--no-merge', '--no-device', '--keep-worktree'];
    const unknown = [...flags].filter(f => !known.includes(f));
    if (unknown.length)
      throw new Stop(
        'usage',
        `unknown flag ${unknown.join(' ')} (resume is not init)`,
      );
    const rest = words.filter(w => !w.startsWith('--'));
    const all = rest.length === 1 && rest[0] === 'all';
    const keys = all ? [] : [...new Set(rest.map(normaliseKey))];
    if (!all && !keys.length)
      throw new Stop('usage', 'no tickets in the arguments');
    const state = {
      runId: now(),
      stamp: INIT.stamp,
      primary: INIT.primary,
      worktree: INIT.worktree,
      runBranch: RUN_BRANCH,
      baseBranch: BASE,
      noMerge: flags.has('--no-merge'),
      noDevice: flags.has('--no-device'),
      keepWorktree: flags.has('--keep-worktree'),
      discoverAll: all,
      halted: null,
      worktreeRemoved: false,
      publishSnapshot: null,
      heartbeat: now(),
      tickets: keys.map(newTicket),
    };
    try {
      fs.writeFileSync(STATE, `${JSON.stringify(state, null, 2)}\n`, {
        flag: 'wx',
      });
    } catch (e) {
      if (e.code === 'EEXIST')
        throw new Stop(
          'error',
          `${STATE} exists: stamp collision; re-run, never reuse it`,
        );
      throw e;
    }
    return {
      ok: true,
      tickets: keys,
      discoverAll: all,
      noMerge: state.noMerge,
      noDevice: state.noDevice,
      keepWorktree: state.keepWorktree,
    };
  },

  add() {
    const key = normaliseKey(KEY);
    const state = loadState();
    if (state.tickets.some(t => t.id === key))
      return { ok: true, existed: true };
    state.tickets.push(newTicket(key));
    state.heartbeat = now();
    saveState(state);
    return { ok: true, existed: false };
  },

  show() {
    const t = ticketOf(loadState(), normaliseKey(KEY));
    const fields = REST[0]?.split(',');
    return fields ? Object.fromEntries(fields.map(f => [f, t[f] ?? null])) : t;
  },

  record() {
    if (!FIELDS[PHASE])
      throw new Stop(
        'usage',
        `--phase must be one of ${Object.keys(FIELDS).join('|')}`,
      );
    if (!FILE)
      throw new Stop(
        'usage',
        '--file <json> is required (never pipe agent text through echo)',
      );
    let input;
    try {
      input = JSON.parse(fs.readFileSync(FILE, 'utf8'));
    } catch (e) {
      throw new Stop('usage', `malformed agent return: ${e.message}`);
    }
    const { result, note, fields, dropped } = validate(PHASE, input);
    const key = normaliseKey(KEY);
    // Validate against the current ticket BEFORE mutate, so a refusal writes nothing.
    const state = loadState();
    const t = ticketOf(state, key);
    const only = (f, ...ks) => Object.keys(f).every(k => ks.includes(k));
    if (
      t.status === 'done' &&
      !(PHASE === 'orchestrator' && only(fields, 'trackerWriteAdd'))
    )
      throw new Stop(
        'usage',
        `${key} is done; only a tracker write may be recorded on it`,
      );
    if (
      PHASE === 'triage'
        ? t.phase !== 'start' || t.status !== 'pending'
        : PHASE !== 'orchestrator' && t.phase !== PHASE
    )
      throw new Stop(
        'usage',
        `${key} is at phase ${t.phase} (${t.status}), not ${PHASE}`,
      );
    if (t.status === 'blocked' && PHASE !== 'orchestrator')
      throw new Stop('usage', `${key} is blocked`);
    for (const [v, s] of [
      ['criticVerdict', 'criticSha'],
      ['verifyVerdict', 'verifiedSha'],
    ])
      if (v in fields && fields[s] !== t.commitSha)
        throw new Stop(
          'usage',
          `${v} is for ${fields[s]}, not commitSha ${t.commitSha}`,
        );
    if ('verifyVerdict' in fields && !('verifiedBaseSha' in fields))
      throw new Stop(
        'usage',
        'a Verify verdict needs verifiedBaseSha, the origin/main it graded against',
      );
    const verdict = { critic: 'criticVerdict', verify: 'verifyVerdict' }[PHASE];
    if (verdict && NEXT[PHASE][result] && fields[verdict] !== result)
      throw new Stop('usage', `result ${result} without a matching ${verdict}`);
    if (fields.notOwned === false && t.notOwned)
      throw new Stop('usage', 'notOwned only goes false -> true');
    if (
      fields.status === 'done' &&
      !(t.mergeCommit || (state.noMerge && t.verifyVerdict === 'pass'))
    )
      throw new Stop(
        'usage',
        'done needs a recorded merge commit (or a --no-merge Verify pass)',
      );
    if (
      fields.status &&
      !['pending', 'in_progress', 'blocked', 'done'].includes(fields.status)
    )
      throw new Stop('usage', `unknown status ${fields.status}`);
    if (
      fields.phase &&
      ![
        'start',
        'build',
        'ship',
        'critic',
        'verify',
        'fix',
        'merge',
        'finish',
      ].includes(fields.phase)
    )
      throw new Stop('usage', `unknown phase ${fields.phase}`);
    if (
      fields.type &&
      !['bug', 'feature', 'spike', 'docs'].includes(fields.type)
    )
      throw new Stop('usage', `unknown type ${fields.type}`);
    if (fields.branch === t.branch) delete fields.branch;
    if ('prNumber' in fields && fields.prNumber === t.prNumber)
      delete fields.prNumber;
    const followup =
      'branch' in fields && fields.branch === `${t.branch}-followup`;
    if ('branch' in fields && !followup)
      throw new Stop('usage', `branch may only become ${t.branch}-followup`);
    if ('prNumber' in fields && !(followup && fields.prNumber === null))
      throw new Stop(
        'usage',
        'prNumber may only be cleared together with the -followup switch',
      );

    return mutate(key, t => {
      const { trackerWriteAdd, clarificationAdd = [] } = fields;
      delete fields.trackerWriteAdd;
      delete fields.clarificationAdd;
      if (followup) Object.assign(fields, { prNumber: null, prUrl: null });
      if (result === 'blocked') {
        fields.status = 'blocked';
        fields.blockedReason ??= note ?? 'blocked (no reason given)';
      } else if (result === 'handoff') {
        fields.phase = PHASE;
      } else if (NEXT[PHASE]?.[result]) {
        fields.phase = NEXT[PHASE][result];
        if (PHASE === 'triage') fields.status = 'pending';
        if (!('handoff' in fields)) fields.handoff = null;
      }
      if (trackerWriteAdd)
        t.trackerWrites.push({ ...trackerWriteAdd, at: now() });
      for (const c of clarificationAdd)
        if (!t.clarification.some(x => x.question === c.question))
          t.clarification.push({ ...c, decidedBy: 'pipeline', posted: false });
      apply(
        t,
        fields,
        PHASE,
        [result, note].filter(Boolean).join(': ') || undefined,
      );
      return {
        ok: true,
        phase: t.phase,
        status: t.status,
        dropped,
        unposted: t.notOwned
          ? []
          : t.clarification
              .filter(c => c.decidedBy === 'pipeline' && !c.posted)
              .map(c => c.question),
      };
    });
  },

  // After the orchestrator's ownership check passed. Records the branch BEFORE any tracker write.
  branch() {
    const key = normaliseKey(KEY);
    if (
      !RUN_BRANCH ||
      !TITLE ||
      !['bug', 'feature', 'spike', 'docs'].includes(TYPE)
    )
      throw new Stop(
        'usage',
        '--run-branch, --title and --type <bug|feature|spike|docs> are required',
      );
    return mutate(key, t => {
      if (t.status === 'blocked' || t.status === 'done')
        throw new Stop('usage', `${key} is ${t.status}`);
      if (git('status', '--short'))
        throw new Stop('error', 'lane is not clean before Start');
      git('fetch', 'origin');
      if (
        !t.branch &&
        git('rev-parse', RUN_BRANCH) !== git('rev-parse', `origin/${BASE}`)
      ) {
        git('checkout', RUN_BRANCH);
        git('reset', '--hard', `origin/${BASE}`); // another run merged since Step 0d
      }
      const slug =
        TITLE.toLowerCase()
          .replace(/[^a-z0-9]+/g, '-')
          .replace(/^-+|-+$/g, '')
          .slice(0, 50)
          .replace(/-+$/, '') || 'ticket';
      const kind = {
        bug: 'fix',
        feature: 'feature',
        spike: 'spike',
        docs: 'docs',
      }[TYPE];
      const branch = t.branch ?? `${kind}/${key.toLowerCase()}-${slug}`;
      if (!BRANCH_RE.test(branch))
        throw new Stop(
          'blocked',
          `branch name ${branch} does not match the pattern`,
        );
      const local =
        run('git', ['rev-parse', '--verify', '--quiet', `refs/heads/${branch}`])
          .code === 0;
      if (t.branch && local) git('checkout', branch);
      else {
        if (local || remoteHead(branch))
          throw new Stop('blocked', `branch collision: ${branch} exists`);
        git('checkout', '--no-track', '-b', branch, RUN_BRANCH);
      }
      apply(
        t,
        {
          title: TITLE,
          type: TYPE,
          branch,
          status: 'in_progress',
          phase: 'build',
        },
        'start',
        `branch ${branch}`,
      );
      return { ok: true, branch };
    });
  },

  ship() {
    const key = normaliseKey(KEY);
    return mutate(key, t => {
      if (t.phase !== 'ship' || t.status !== 'in_progress')
        throw new Stop(
          'usage',
          `${key} is at ${t.phase} (${t.status}), not ship`,
        );
      const fail = (reason, output = '') => {
        apply(
          t,
          {
            verifyFindings: `Ship failed: ${reason}\n${output}`.trim(),
            phase: 'fix',
          },
          'ship',
          `fail: ${reason}`,
        );
        return { ok: false, route: 'fix', reason };
      };
      if (!BRANCH_RE.test(t.branch.replace(/-followup$/, '')))
        throw new Stop('blocked', `refusing to push ${t.branch}`);
      if (git('branch', '--show-current') !== t.branch)
        throw new Stop('error', `lane is not on ${t.branch}`);
      if (git('status', '--short'))
        return fail('uncommitted changes in the lane');
      if (/^wip:/m.test(git('log', '--format=%s', `origin/${BASE}..HEAD`)))
        return fail(
          'a wip: commit is still on the branch; squash it before shipping',
        );
      const files = changedFiles();
      if (files.some(f => OWNER_ONLY_FILES.test(f)))
        throw new Stop(
          'blocked',
          "the diff changes release/signing-cert.sha256, the release signer: the owner's change, never a run's",
        );
      const head = git('rev-parse', 'HEAD');
      const gatesChanged = files.some(f => GATE_FILES.test(f));
      if (head !== t.gatedSha || gatesChanged) {
        const g = runGates(files);
        if (!g.ok) return fail(`gate ${g.gate}`, g.output);
      }

      // Adopt an open PR for this branch: a crash between create and record must not loop.
      let { prNumber, prUrl } = t;
      if (!prNumber) {
        const open = JSON.parse(
          sh('gh', [
            'pr',
            'list',
            '--head',
            t.branch,
            '--state',
            'open',
            '--json',
            'number,url,baseRefName',
          ]),
        );
        if (open.length) ({ number: prNumber, url: prUrl } = open[0]);
      }
      if (prNumber) {
        const pr = prView(prNumber, 'state,baseRefName');
        if (pr.state === 'MERGED')
          return fail(
            'PR was merged outside the run; work on a -followup branch',
          );
        if (pr.state === 'CLOSED')
          throw new Stop('blocked', `PR #${prNumber} was closed unmerged`);
        if (pr.baseRefName !== BASE)
          return fail(`PR base is ${pr.baseRefName}, not ${BASE}`);
      }

      const remote = remoteHead(t.branch);
      if (remote && remote !== t.commitSha && remote !== head)
        throw new Stop(
          'blocked',
          `origin/${t.branch} is ${remote}, a commit this run did not push`,
        );
      if (remote !== head) {
        const ref = `refs/heads/${t.branch}`;
        const push = remote
          ? run('git', [
              'push',
              `--force-with-lease=${ref}:${remote}`,
              'origin',
              `${ref}:${ref}`,
            ])
          : run('git', ['push', '-u', 'origin', `${ref}:${ref}`]); // a -followup's first push: nothing to lease
        if (push.code !== 0 && remoteHead(t.branch) !== head)
          return fail('push refused', push.out);
      }
      if (remoteHead(t.branch) !== head)
        return fail('push did not read back as HEAD');
      if (t.commitSha !== head)
        apply(
          t,
          { commitSha: head, mergeAttempts: 0 },
          'ship',
          `pushed ${head.slice(0, 8)}`,
        );

      const round = t.roundNote ? `Round ${t.fixRound}: ${t.roundNote}` : null;
      if (!prNumber) {
        const body = [
          t.prBody ?? t.implementationSummary ?? '',
          round,
          'NOT VERIFIED BY A HUMAN. NOT VERIFIED ON DEVICE (Verify records what the phone showed, if it ran).',
          `Resolves ${key}`,
          '🤖 Generated with [Claude Code](https://claude.com/claude-code)',
        ]
          .filter(Boolean)
          .join('\n\n');
        const created = run('gh', [
          'pr',
          'create',
          '--base',
          BASE,
          '--head',
          t.branch,
          '--title',
          t.prTitle ?? git('log', '-1', '--format=%s'),
          '--body-file',
          tmpFile(body),
        ]);
        const m = created.stdout.match(/https:\/\/\S+\/pull\/(\d+)/);
        if (created.code !== 0 || !m)
          return fail('gh pr create failed', created.out);
        prUrl = m[0];
        prNumber = Number(m[1]);
      } else if (round && !appendToPrBody(prNumber, round)) {
        return fail('the PR body Round line did not read back');
      }
      apply(
        t,
        { prNumber, prUrl, roundNote: null, phase: 'critic' },
        'ship',
        `PR #${prNumber}`,
      );
      return { ok: true, prNumber, prUrl, commitSha: head, ...riskOf(files) };
    });
  },

  risk() {
    const t = ticketOf(loadState(), normaliseKey(KEY));
    return riskOf(changedFiles(t.commitSha));
  },

  'pr-append'() {
    const t = ticketOf(loadState(), normaliseKey(KEY));
    if (!t.prNumber || !FILE) throw new Stop('usage', 'needs a PR and --file');
    if (!appendToPrBody(t.prNumber, fs.readFileSync(FILE, 'utf8').trim()))
      throw new Stop('error', 'PR body did not read back');
    return { ok: true };
  },

  // Preflight only. The orchestrator runs the printed `gh pr merge` as a plain Bash call so the
  // permission layer sees it; inside `node` it would be invisible.
  merge() {
    const key = normaliseKey(KEY);
    return mutate(key, (t, state) => {
      if (t.phase !== 'merge' || t.status !== 'in_progress')
        throw new Stop(
          'usage',
          `${key} is at ${t.phase} (${t.status}), not merge`,
        );
      if (state.noMerge !== false)
        throw new Stop(
          'blocked',
          'run is --no-merge (or its state predates noMerge): PR left open',
        );
      const sha = t.commitSha;
      if (
        !['pass', 'concerns'].includes(t.criticVerdict) ||
        t.verifyVerdict !== 'pass' ||
        !sha ||
        t.criticSha !== sha ||
        t.verifiedSha !== sha
      )
        throw new Stop(
          'error',
          'merge called without both verdicts on the current commitSha',
        );
      git('fetch', 'origin');
      const base = git('rev-parse', `origin/${BASE}`);
      if (base !== t.verifiedBaseSha) {
        apply(
          t,
          {
            verifyFindings: `origin/${BASE} moved from ${t.verifiedBaseSha} to ${base} after Verify; rebase onto it and re-verify.`,
            phase: 'fix',
          },
          'merge',
          'base moved',
        );
        return { ok: false, route: 'fix', reason: 'base moved' };
      }
      const pr = prView(t.prNumber, 'state,baseRefName,headRefOid');
      if (pr.headRefOid !== sha)
        throw new Stop(
          'blocked',
          `PR head ${pr.headRefOid} is not the graded ${sha}`,
        );
      if (pr.state === 'CLOSED')
        throw new Stop('blocked', `PR #${t.prNumber} was closed unmerged`);
      if (pr.state === 'MERGED') return { ok: true, run: [] };
      if (pr.baseRefName !== BASE)
        throw new Stop('blocked', `PR base is ${pr.baseRefName}`);
      t.mergeAttempts = (t.mergeAttempts ?? 0) + 1;
      if (t.mergeAttempts > 2)
        throw new Stop('blocked', 'gh pr merge did not land in two attempts');
      return {
        ok: true,
        run: [`gh pr merge ${t.prNumber} --squash --match-head-commit ${sha}`],
      };
    });
  },

  merged() {
    const key = normaliseKey(KEY);
    return mutate(key, t => {
      if (t.phase !== 'merge')
        throw new Stop('usage', `${key} is at ${t.phase}, not merge`);
      const sha = t.commitSha;
      const s = prView(t.prNumber, 'state,mergeable,headRefOid,mergeCommit');
      if (s.state !== 'MERGED') {
        if (s.headRefOid !== sha)
          throw new Stop(
            'blocked',
            'PR head moved after Verify; someone else pushed',
          );
        if (s.mergeable === 'CONFLICTING') {
          apply(
            t,
            {
              verifyFindings: `Merge conflict with origin/${BASE}; rebase.`,
              phase: 'fix',
            },
            'merge',
            'conflict',
          );
          return { ok: false, route: 'fix', reason: 'conflict' };
        }
        spawnSync('sleep', ['10']); // mergeable is computed lazily; UNKNOWN usually settles
        return { ok: true, retry: true };
      }
      if (s.headRefOid !== sha)
        throw new Stop(
          'error',
          'PR reads back MERGED at a head that was not graded',
        );
      apply(
        t,
        { mergeCommit: s.mergeCommit?.oid ?? null, phase: 'finish' },
        'merge',
        'merged',
      );
      const remote = remoteHead(t.branch);
      if (remote && remote !== sha)
        throw new Stop(
          'blocked',
          `merged; origin/${t.branch} moved to ${remote} after, so it was kept`,
        );
      return {
        ok: true,
        mergeCommit: s.mergeCommit?.oid,
        run: remote
          ? [`gh api -X DELETE repos/{owner}/{repo}/git/refs/heads/${t.branch}`]
          : [],
      };
    });
  },

  finish() {
    const key = normaliseKey(KEY);
    if (!RUN_BRANCH) throw new Stop('usage', '--run-branch is required');
    return mutate(key, t => {
      if (t.phase !== 'finish' || !t.mergeCommit)
        throw new Stop(
          'usage',
          `${key} has no recorded merge commit; Finish needs one`,
        );
      if (git('status', '--short')) {
        git('add', '-A');
        git('commit', '-q', '--no-verify', '-m', 'wip: left after merge');
        throw new Stop(
          'blocked',
          'lane had uncommitted work after merge; committed as wip on the ticket branch (not pushed)',
        );
      }
      git('checkout', '-q', RUN_BRANCH);
      git('fetch', 'origin');
      git('reset', '-q', '--hard', `origin/${BASE}`);
      if (remoteHead(t.branch))
        throw new Stop(
          'blocked',
          `merged, but origin/${t.branch} was not deleted`,
        );
      const local = run('git', [
        'rev-parse',
        '--verify',
        '--quiet',
        `refs/heads/${t.branch}`,
      ]);
      if (local.code === 0) {
        if (local.stdout.trim() !== t.commitSha)
          throw new Stop(
            'blocked',
            `local ${t.branch} holds commits that were not merged`,
          );
        git('branch', '-D', t.branch); // squash merge makes `branch -d` refuse; equality is the check
      }
      apply(t, { laneReset: true }, 'finish', 'lane resynced');
      return {
        ok: true,
        tracker: t.notOwned
          ? 'skip: not owned'
          : 'set Done, read back, then record status done',
      };
    });
  },

  'device-check'() {
    const state = loadState();
    const t = ticketOf(state, normaliseKey(KEY));
    if (state.noDevice)
      return {
        ok: true,
        allowed: false,
        line: 'DEVICE: NOT RUN (--no-device)',
      };
    const files = changedFiles();
    if (files.includes('scripts/lib/device.sh'))
      return {
        ok: true,
        allowed: false,
        line: "DEVICE: NOT RUN (the diff changes scripts/lib/device.sh, which holds the run's data-clear guard)",
      };
    const disposable = fs.existsSync(
      path.join(state.primary, '.claude', 'device-data-disposable'),
    );
    return {
      ok: true,
      allowed: true,
      branch: t.branch,
      clearsAllowed: disposable,
    };
  },

  // Run-level fields the orchestrator owns; still written here, so there is one writer.
  'set-run'() {
    if (!FILE) throw new Stop('usage', '--file <json> is required');
    let input;
    try {
      input = JSON.parse(fs.readFileSync(FILE, 'utf8'));
    } catch (e) {
      throw new Stop('usage', `malformed: ${e.message}`);
    }
    const types = {
      halted: v => v === null || KEY_RE.test(v),
      worktreeRemoved: v => typeof v === 'boolean',
      gatesAtStart: v => typeof v === 'string',
    };
    if (!input || typeof input !== 'object' || Array.isArray(input))
      throw new Stop('usage', 'not a JSON object');
    for (const [k, v] of Object.entries(input))
      if (!types[k]?.(v))
        throw new Stop(
          'usage',
          `run field ${k} is not settable as ${JSON.stringify(v)}`,
        );
    const state = loadState();
    Object.assign(state, input);
    state.heartbeat = now();
    saveState(state);
    return { ok: true };
  },

  guard() {
    const state = loadState();
    const snap = publishSnapshot();
    if (SNAPSHOT) {
      state.publishSnapshot = snap;
      saveState(state);
      return {
        ok: true,
        tags: snap.tags.length,
        releases: snap.releases.length,
      };
    }
    if (!state.publishSnapshot)
      throw new Stop(
        'error',
        'no publish snapshot; run guard --snapshot at Step 0c',
      );
    const added = k =>
      snap[k].filter(x => !state.publishSnapshot[k].includes(x));
    const removed = k =>
      state.publishSnapshot[k].filter(x => !snap[k].includes(x));
    const diff = {
      tagsAdded: added('tags'),
      tagsRemoved: removed('tags'),
      releasesAdded: added('releases'),
      releasesRemoved: removed('releases'),
    };
    if (Object.values(diff).some(a => a.length))
      throw new Stop(
        'error',
        `a tag or release changed during the run (F-Droid builds new tags): ${JSON.stringify(diff)}`,
      );
    return { ok: true };
  },
};

try {
  if (!commands[cmd])
    throw new Stop('usage', `commands: ${Object.keys(commands).join(' | ')}`);
  if (!['init', 'guard', 'set-run'].includes(cmd) && !KEY)
    throw new Stop('usage', `${cmd} needs a ticket KEY`);
  const out = commands[cmd]();
  console.log(JSON.stringify(out));
  process.exit(out?.ok === false ? EXIT.fail : EXIT.ok);
} catch (e) {
  const kind = e instanceof Stop ? e.kind : 'error';
  if (kind === 'blocked' && STATE && KEY) {
    try {
      mutate(normaliseKey(KEY), t =>
        apply(
          t,
          { status: 'blocked', blockedReason: e.message },
          cmd,
          `blocked: ${e.message}`,
        ),
      );
    } catch {}
  }
  console.log(JSON.stringify({ ok: false, [kind]: e.message }));
  process.exit(EXIT[kind] ?? EXIT.error);
}
