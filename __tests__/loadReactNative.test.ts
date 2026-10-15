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

test("loads the components the app's screens render", () => {
  expect(loaded).toEqual(expect.arrayContaining(['FlatList', 'Modal', 'Pressable', 'ScrollView', 'Text', 'View']));
});
