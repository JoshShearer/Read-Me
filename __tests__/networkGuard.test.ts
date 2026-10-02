/// <reference types="node" />
// Node-only test: the app source never sees these types.
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

// R-M09.2 and AGENTS.md 6, JS half: no app source may reference the JS network APIs. The
// Kotlin half (only Fetcher opens a connection) lands with Fetcher in Phase 2.
const ROOT = join(__dirname, '..');
const BANNED = /\b(fetch|XMLHttpRequest|WebSocket)\b/;
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
});

test('no app source references fetch, XMLHttpRequest or WebSocket', () => {
  const files = [
    ...sources(join(ROOT, 'src')),
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
