import {test} from 'node:test';
import assert from 'node:assert/strict';
import {parseCoordinates, parsePomLicenses} from '../make-notices.mjs';

test('coordinates come from the dependency tree, resolved versions win, deduped', () => {
  const tree = `releaseRuntimeClasspath
+--- androidx.work:work-runtime-ktx:2.12.0
|    \\--- androidx.work:work-runtime:2.12.0
|         +--- androidx.core:core:1.9.0 -> 1.13.1 (*)
|         \\--- org.jetbrains.kotlin:kotlin-stdlib:{strictly 2.2.0} -> 2.2.0 (c)
+--- project :react-native-safe-area-context
\\--- androidx.core:core:1.13.1`;
  assert.deepEqual(parseCoordinates(tree), [
    'androidx.core:core:1.13.1',
    'androidx.work:work-runtime-ktx:2.12.0',
    'androidx.work:work-runtime:2.12.0',
    'org.jetbrains.kotlin:kotlin-stdlib:2.2.0',
  ]);
});

test('licenses are read from a POM', () => {
  const pom = `<project><licenses><license><name>The Apache Software License, Version 2.0</name>
    <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url></license></licenses></project>`;
  assert.deepEqual(parsePomLicenses(pom), [
    {name: 'The Apache Software License, Version 2.0', url: 'https://www.apache.org/licenses/LICENSE-2.0.txt'},
  ]);
  assert.deepEqual(parsePomLicenses('<project/>'), []);
});
