/// <reference types="node" />
// Node-only test: the app source never sees these types.
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

// R-M09.2 and AGENTS.md 6, JS half: no app source may reference the JS network APIs. The
// Kotlin half (only Fetcher opens a connection) lands with Fetcher in Phase 2.
const ROOT = join(__dirname, '..');
// A hyphen after the word is a state name ('fetch-failed', srs.md data model), never an API.
const BANNED = /\b(fetch|XMLHttpRequest|WebSocket)\b(?!-)/;
const SOURCE = /\.(ts|tsx|js|jsx)$/;

function sources(dir: string): string[] {
  if (!existsSync(dir)) return [];
  return readdirSync(dir).flatMap(name => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return sources(path);
    // Generated fixture modules hold page HTML as data, not code.
    return SOURCE.test(name) && !name.endsWith('.generated.ts') ? [path] : [];
  });
}

test('the guard pattern catches each banned API', () => {
  expect(BANNED.test('await fetch(url)')).toBe(true);
  expect(BANNED.test('new XMLHttpRequest()')).toBe(true);
  expect(BANNED.test('new WebSocket(u)')).toBe(true);
  expect(BANNED.test('the body was fetched natively')).toBe(false);
  expect(BANNED.test("state === 'fetch-failed'")).toBe(false);
  expect(BANNED.test('globalThis.fetch')).toBe(true);
});

test('no app source references fetch, XMLHttpRequest or WebSocket', () => {
  const files = [
    // guard.ts names the APIs only to replace them with throwing stubs (F16).
    ...sources(join(ROOT, 'src')).filter(
      f => f !== join(ROOT, 'src', 'net', 'guard.ts'),
    ),
    ...['App.tsx', 'index.js', 'index.devcheck.js']
      .map(f => join(ROOT, f))
      .filter(existsSync),
  ];
  expect(files.length).toBeGreaterThan(0);
  const hits = files
    .filter(f => BANNED.test(readFileSync(f, 'utf8')))
    .map(f => f.slice(ROOT.length + 1));
  expect(hits).toEqual([]);
});

// R-M09.2 as amended (F16), Kotlin half: only Fetcher makes an outbound connection, and a
// ServerSocket is allowed only in BridgeServer (Phase 5).
const KOTLIN_ROOT = join(ROOT, 'android/app/src/main/java');
const OUTBOUND = /\bSocket\(|\.openConnection\(|\bOkHttpClient\b|\bHttpURLConnection\b/;
const INBOUND = /\bServerSocket\(/;

function kotlinSources(dir: string): string[] {
  if (!existsSync(dir)) return [];
  return readdirSync(dir).flatMap(name => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return kotlinSources(path);
    return name.endsWith('.kt') ? [path] : [];
  });
}

test('the Kotlin patterns catch each connection shape', () => {
  expect(OUTBOUND.test('val s = Socket(host, 80)')).toBe(true);
  expect(OUTBOUND.test('url.openConnection()')).toBe(true);
  expect(OUTBOUND.test('OkHttpClient.Builder()')).toBe(true);
  expect(OUTBOUND.test('ServerSocket(8787)')).toBe(false);
  expect(INBOUND.test('ServerSocket(8787)')).toBe(true);
});

test('only Fetcher connects out, and ServerSocket lives only in BridgeServer', () => {
  const offenders = kotlinSources(KOTLIN_ROOT).flatMap(path => {
    const text = readFileSync(path, 'utf8');
    const file = path.split('/').pop();
    const found: string[] = [];
    if (OUTBOUND.test(text) && file !== 'Fetcher.kt') found.push(`${path}: outbound`);
    if (INBOUND.test(text) && file !== 'BridgeServer.kt') found.push(`${path}: ServerSocket`);
    return found;
  });
  expect(offenders).toEqual([]);
  // The rule must see the real tree, not an empty directory.
  expect(kotlinSources(KOTLIN_ROOT).some(p => p.endsWith('Fetcher.kt'))).toBe(true);
});
