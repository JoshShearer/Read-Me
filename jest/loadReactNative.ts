/**
 * Loads the React Native modules the app imports, at a test file's top level.
 *
 * React Native's index exports its components through lazy getters, so a screen's first render
 * requires them, and with no Jest cache Babel-transforms them, inside the file's first test and
 * its 5 s timeout. CI starts with no cache: on ci run 38776437438 (REA-55, d9c196c) that first
 * render passed 5 s in App.test.tsx and screens.test.tsx. Locally, with an empty cache on one CPU,
 * App's first render took 6.2 s against 0.44 s with a warm one; ScrollView alone took 3.7 s and
 * Text 1.4 s. A test file's top level has no timeout, so the cost lands there instead.
 *
 * The names come from the app's own `from 'react-native'` imports, so a component a screen starts
 * using is loaded here without anyone having to remember this file.
 */
import fs from 'fs';
import path from 'path';
import * as ReactNative from 'react-native';

const root = path.resolve(__dirname, '..');

function sources(dir: string): string[] {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap(e => {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) return sources(p);
    return /\.tsx?$/.test(e.name) ? [p] : [];
  });
}

/** The value names a source imports from 'react-native' (`X as Y` gives X; type imports none). */
export function reactNativeImports(text: string): string[] {
  const names: string[] = [];
  for (const m of text.matchAll(/import\s+(type\s+)?\{([^}]*)\}\s*from\s*'react-native'/g)) {
    if (m[1]) continue;
    for (const part of m[2].split(',')) {
      const name = part.trim().split(/\s+as\s+/)[0].trim();
      if (name && !name.startsWith('type ')) names.push(name);
    }
  }
  return names;
}

const names = new Set(
  [path.join(root, 'App.tsx'), ...sources(path.join(root, 'src'))].flatMap(f =>
    reactNativeImports(fs.readFileSync(f, 'utf8')),
  ),
);
const exported = ReactNative as unknown as Record<string, unknown>;
for (const name of names) {
  // Reading the getter is what requires the module.
  void exported[name];
}

export const loaded = [...names].sort();
