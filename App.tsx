// A plain list of items. Tap a row to play it, tap again to pause or resume; the marker
// shows what the service is doing. The real list, Trim and Reader screens are Phase 4.
import React, { useCallback, useEffect, useState } from 'react';
import {
  FlatList,
  Linking,
  Pressable,
  StatusBar,
  StyleSheet,
  Text,
  View,
} from 'react-native';
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
import {
  engineProblem,
  getPlayback,
  marker,
  onPlayback,
  toggle,
  type Playback,
} from './src/library/playback';

function Library() {
  const insets = useSafeAreaInsets();
  const [items, setItems] = useState<Item[]>([]);
  const [playback, setPlayback] = useState<Playback | null>(null);

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

  useEffect(() => {
    getPlayback().then(setPlayback, () => undefined);
    return onPlayback(setPlayback);
  }, []);

  const onPress = useCallback(
    (id: number) => {
      toggle(id, playback).catch(() => undefined);
    },
    [playback],
  );

  return (
    <View style={[styles.root, { paddingTop: insets.top }]}>
      {engineProblem(playback) ? (
        <View style={styles.problem}>
          <Text>
            No offline text-to-speech voice is available, so Read Me cannot read aloud.
          </Text>
          <Pressable
            onPress={() =>
              Linking.sendIntent('com.android.settings.TTS_SETTINGS').catch(
                () => undefined,
              )
            }>
            <Text style={styles.link}>Open text-to-speech settings</Text>
          </Pressable>
        </View>
      ) : null}
      <FlatList
        data={items}
        keyExtractor={item => String(item.id)}
        ListEmptyComponent={<Text style={styles.empty}>Share a link or text to Read Me.</Text>}
        renderItem={({ item }) => {
          const mark = marker(item, playback);
          return (
            <Pressable style={styles.row} onPress={() => onPress(item.id)}>
              <Text style={styles.title}>{item.title}</Text>
              <Text style={styles.state}>
                {item.state}
                {item.failReason ? `: ${item.failReason}` : ''}
              </Text>
              {mark ? <Text style={styles.state}>{mark}</Text> : null}
            </Pressable>
          );
        }}
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
  problem: { padding: 16, gap: 8 },
  link: { textDecorationLine: 'underline' },
  row: { paddingHorizontal: 16, paddingVertical: 12 },
  title: { fontSize: 16 },
  state: { fontSize: 13, opacity: 0.7 },
});
