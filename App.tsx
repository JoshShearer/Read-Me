// A plain list of items and their states, so Phase 2 can be seen and checked on the phone.
// The real list, Trim and Reader screens are Phase 4 (R-M01, R-M10).
import React, { useCallback, useEffect, useState } from 'react';
import { FlatList, StatusBar, StyleSheet, Text, View } from 'react-native';
import {
  SafeAreaProvider,
  useSafeAreaInsets,
} from 'react-native-safe-area-context';
import {
  drainFetched,
  listItems,
  onItemsChanged,
  type Item,
} from './src/library/library';

function Library() {
  const insets = useSafeAreaInsets();
  const [items, setItems] = useState<Item[]>([]);

  const refresh = useCallback(() => {
    listItems().then(setItems, () => setItems([]));
  }, []);

  useEffect(() => {
    refresh();
    drainFetched().catch(() => undefined);
    return onItemsChanged(() => {
      refresh();
      drainFetched().catch(() => undefined);
    });
  }, [refresh]);

  return (
    <View style={[styles.root, { paddingTop: insets.top }]}>
      <FlatList
        data={items}
        keyExtractor={item => String(item.id)}
        ListEmptyComponent={<Text style={styles.empty}>Share a link or text to Read Me.</Text>}
        renderItem={({ item }) => (
          <View style={styles.row}>
            <Text style={styles.title}>{item.title}</Text>
            <Text style={styles.state}>
              {item.state}
              {item.failReason ? `: ${item.failReason}` : ''}
            </Text>
          </View>
        )}
      />
    </View>
  );
}

export default function App() {
  return (
    <SafeAreaProvider>
      <StatusBar barStyle="default" />
      <Library />
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1 },
  empty: { padding: 24 },
  row: { paddingHorizontal: 16, paddingVertical: 12 },
  title: { fontSize: 16 },
  state: { fontSize: 13, opacity: 0.7 },
});
