#!/usr/bin/env node
// REA-43: build on JDK 21. React Native's Gradle plugin hardcodes a JDK 17 toolchain (0.87.1,
// still in 0.88.0-rc.4 and the 0.89 nightly of 2026-10-09): for its own compile, in four
// build.gradle.kts files, and for every app module's Kotlin, in JdkConfiguratorUtils.kt. F-Droid's
// image has only JDK 21 (fdroiddata !51088 asked us to use it). The app's bytecode target stays
// 17: Kotlin's jvmTarget is pinned, since a JDK 21 toolchain alone moves it to 21, and the Kotlin
// plugin then refuses the mismatch with Java's 17.
// Every build must run this, ours and F-Droid's: the patched plugin is a build input, and a
// Gradle transform hash that follows it ends up in libreact_codegen_safeareacontext.so (an assert
// path), so a build with and one without it never match. postinstall, build-release.sh and the
// recipe's prebuild run it. Idempotent; refuses when the upstream text is not what it expects.
import { readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const PLUGIN = 'node_modules/@react-native/gradle-plugin';
const KTS_FROM = 'kotlin { jvmToolchain(17) }';
const KTS_TO = 'kotlin { jvmToolchain(21) }';
const KT_FROM = 'project.kotlinExtension.jvmToolchain(17)';
const KT_TO =
  'project.kotlinExtension.jvmToolchain(21)\n' +
  '        project.tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile::class.java).configureEach {\n' +
  '          it.compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)\n' +
  '        }';

// [file, from, to, how many times upstream has it]
export const EDITS = [
  ['settings-plugin/build.gradle.kts', KTS_FROM, KTS_TO, 1],
  ['shared/build.gradle.kts', KTS_FROM, KTS_TO, 1],
  ['shared-testutil/build.gradle.kts', KTS_FROM, KTS_TO, 1],
  ['react-native-gradle-plugin/build.gradle.kts', KTS_FROM, KTS_TO, 1],
  [
    'react-native-gradle-plugin/src/main/kotlin/com/facebook/react/utils/JdkConfiguratorUtils.kt',
    KT_FROM,
    KT_TO,
    2,
  ],
];

const count = (s, sub) => s.split(sub).length - 1;

// Every Gradle script and Kotlin source in the plugin (its build output aside).
function* sources(dir) {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) {
      if (e.name !== 'build' && e.name !== '.gradle') {
        yield* sources(p);
      }
    } else if (/\.(kts|kt|gradle)$/.test(e.name)) {
      yield p;
    }
  }
}

// patch(root): 'patched' or 'already'. Throws, changing nothing, unless every file is either
// exactly upstream or exactly patched, and nothing anywhere still asks for a JDK 17 toolchain.
// write: false only reports what it would do.
export function patch(root, { write = true } = {}) {
  const plan = EDITS.map(([rel, from, to, n]) => {
    const file = join(root, PLUGIN, rel);
    const text = readFileSync(file, 'utf8');
    if (count(text, from) === n && count(text, to) === 0) {
      return { file, text: text.split(from).join(to) };
    }
    if (count(text, from) === 0 && count(text, to) === n) {
      return { file, text: null };
    }
    throw new Error(
      `patch-rn-jdk21: ${rel} is neither upstream nor patched (React Native changed?); expected ` +
        `${n} x "${from}"`,
    );
  });
  const planned = new Map(plan.map(p => [p.file, p.text]));
  for (const file of sources(join(root, PLUGIN))) {
    const after = planned.get(file) ?? readFileSync(file, 'utf8');
    if (/jvmToolchain\(\s*17\s*\)/.test(after)) {
      throw new Error(`patch-rn-jdk21: ${file} asks for a JDK 17 toolchain and this script does not patch it`);
    }
  }
  const todo = plan.filter(p => p.text !== null);
  if (write) {
    for (const { file, text } of todo) {
      writeFileSync(file, text);
    }
  }
  return todo.length ? 'patched' : 'already';
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const root = join(fileURLToPath(new URL('.', import.meta.url)), '..');
  try {
    const r = patch(root);
    console.log(
      r === 'patched'
        ? 'patch-rn-jdk21: React Native Gradle plugin set to JDK 21'
        : 'patch-rn-jdk21: already JDK 21',
    );
  } catch (e) {
    console.error(e.message);
    process.exit(1);
  }
}
