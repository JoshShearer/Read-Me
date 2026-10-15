import { loaded, reactNativeImports } from '../jest/loadReactNative';

test('reads the value names a source imports from react-native', () => {
  expect(
    reactNativeImports(
      "import { View, Text as RNText, type ViewStyle } from 'react-native';\n" +
        "import type { StyleProp } from 'react-native';\n" +
        "import { useState } from 'react';",
    ),
  ).toEqual(['View', 'Text']);
});

const screens = ['FlatList', 'Modal', 'Pressable', 'ScrollView', 'Text', 'View'];

test("finds the components the app's screens render", () => {
  expect(loaded).toEqual(expect.arrayContaining(screens));
});

test('reads each of them from react-native when it is imported', () => {
  // A react-native whose every export records its read: the preload is what makes CI's cold
  // first render fit its 5 s, and it only works if it reads the getters at import time.
  const read: string[] = [];
  jest.isolateModules(() => {
    jest.doMock('react-native', () =>
      new Proxy({}, { get: (_t, k) => (k === '__esModule' ? true : (typeof k === 'string' && read.push(k), {})) }),
    );
    require('../jest/loadReactNative');
  });
  expect(read).toEqual(expect.arrayContaining(screens));
});
