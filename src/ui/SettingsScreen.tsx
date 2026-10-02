// R-M01 Settings: voice (R-M06: offline voices only), default rate, storage, Licenses (R-M13).
// The bridge row arrives with BridgeServer in Phase 5.
import React, { useCallback, useEffect, useState } from 'react';
import { Alert, Linking, Pressable, ScrollView, Text, View } from 'react-native';
import { deleteItem, listItems, onItemsChanged, type Item } from '../library/library';
import { engineBlocked, getEngine, setRate, setVoice, type Engine } from '../library/playback';
import Native from '../native/NativeReadMeSpeech';
import { formatRate, stepRate, visibleItems } from './model';
import { ui } from './ui';

export function SettingsScreen({ onLicenses }: { onLicenses: () => void }) {
  const [engine, setEngine] = useState<Engine | null>(null);
  const [rate, setRateState] = useState(2);
  const [items, setItems] = useState<Item[]>([]);

  const loadEngine = useCallback(() => {
    getEngine().then(setEngine, () => setEngine({ status: 'no-engine', voices: [], selected: null }));
  }, []);
  const loadItems = useCallback(() => {
    listItems().then(setItems, () => setItems([]));
  }, []);

  useEffect(() => {
    loadEngine();
    loadItems();
    Native.getRate().then(setRateState, () => undefined);
    return onItemsChanged(loadItems);
  }, [loadEngine, loadItems]);

  const archived = visibleItems(items, true);
  const changeRate = (dir: 1 | -1) => {
    setRate(stepRate(rate, dir)).then(setRateState, () => undefined);
  };
  const deleteArchived = () =>
    Alert.alert(`Delete ${archived.length} archived items?`, 'Their text is removed from this phone.', [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'Delete',
        style: 'destructive',
        onPress: () => {
          for (const i of archived) deleteItem(i.id).catch(() => undefined);
        },
      },
    ]);

  return (
    <ScrollView style={ui.screen}>
      <View style={ui.header}>
        <Text style={ui.headerTitle}>Settings</Text>
      </View>

      <Text style={[ui.row, ui.title]}>Voice</Text>
      {engine === null ? (
        <Text style={[ui.row, ui.small]}>Checking the text-to-speech engine...</Text>
      ) : engineBlocked(engine.status) ? (
        <View style={ui.card}>
          <Text>No offline text-to-speech voice is available.</Text>
          <Pressable
            style={ui.button}
            accessibilityLabel="open tts settings"
            onPress={() => {
              Linking.sendIntent('com.android.settings.TTS_SETTINGS').catch(() => undefined);
            }}>
            <Text style={ui.buttonText}>Open text-to-speech settings</Text>
          </Pressable>
        </View>
      ) : (
        engine.voices.map(v => (
          <Pressable
            key={v.name}
            style={ui.row}
            accessibilityLabel={`voice ${v.name}`}
            onPress={() => {
              setVoice(v.name).then(loadEngine, () => undefined);
            }}>
            <Text>{`${v.name === engine.selected ? '● ' : '○ '}${v.name}`}</Text>
            <Text style={ui.small}>{v.language}</Text>
          </Pressable>
        ))
      )}
      <Text style={[ui.row, ui.small]}>A new voice applies from the next play.</Text>

      <Text style={[ui.row, ui.title]}>Default rate</Text>
      <View style={[ui.actions, ui.row]}>
        <Pressable style={ui.button} accessibilityLabel="default slower" onPress={() => changeRate(-1)}>
          <Text style={ui.buttonText}>−</Text>
        </Pressable>
        <Text>{formatRate(rate)}</Text>
        <Pressable style={ui.button} accessibilityLabel="default faster" onPress={() => changeRate(1)}>
          <Text style={ui.buttonText}>+</Text>
        </Pressable>
      </View>

      <Text style={[ui.row, ui.title]}>Storage</Text>
      <Text style={[ui.row, ui.small]}>{`${items.length} items, ${archived.length} archived`}</Text>
      {archived.length > 0 ? (
        <Pressable style={[ui.button, ui.row]} accessibilityLabel="delete archived" onPress={deleteArchived}>
          <Text style={ui.buttonText}>Delete archived items</Text>
        </Pressable>
      ) : null}

      <Pressable style={ui.row} accessibilityLabel="licenses" onPress={onLicenses}>
        <Text style={ui.title}>Licenses</Text>
        <Text style={ui.small}>Third-party software in Read Me</Text>
      </Pressable>
    </ScrollView>
  );
}
