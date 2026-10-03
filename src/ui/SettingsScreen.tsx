// R-M01 Settings: voice (R-M06: offline voices only), default rate, storage, Licenses (R-M13).
// R-M12: the Obsidian bridge row (on/off, port, pairing token with Copy).
import React, { useCallback, useEffect, useState } from 'react';
import { Alert, Linking, Pressable, ScrollView, TextInput, useColorScheme, View } from 'react-native';
import { Text } from './Text';
import { palette } from './theme';
import {
  copyBridgeToken,
  ensureNotifications,
  notificationsAllowed,
  getBridge,
  onBridge,
  regenerateBridgeToken,
  setBridgeEnabled,
  setBridgePort,
  type Bridge,
} from '../library/bridge';
import { deleteItem, listItems, onItemsChanged, type Item } from '../library/library';
import { engineBlocked, getEngine, setRate, setVoice, type Engine } from '../library/playback';
import Native from '../native/NativeReadMeSpeech';
import { bridgeStatus, formatRate, parsePort, stepRate, visibleItems } from './model';
import { ui } from './ui';

export function SettingsScreen({ onLicenses }: { onLicenses: () => void }) {
  const colors = palette(useColorScheme());
  const [engine, setEngine] = useState<Engine | null>(null);
  const [rate, setRateState] = useState(2);
  const [items, setItems] = useState<Item[]>([]);
  const [bridge, setBridge] = useState<Bridge | null>(null);
  const [portText, setPortText] = useState('');
  const [portError, setPortError] = useState(false);
  const [copied, setCopied] = useState(false);
  const [notifyOff, setNotifyOff] = useState(false);
  const [refused, setRefused] = useState(false);

  useEffect(() => {
    getBridge().then(b => {
      setBridge(b);
      setPortText(String(b.port));
    }, () => undefined);
    // R-M12: the permission can be revoked at any time; check it each time Settings opens.
    notificationsAllowed().then(ok => setNotifyOff(!ok), () => undefined);
    return onBridge(setBridge);
  }, []);

  const applyPort = () => {
    const p = parsePort(portText);
    setPortError(p === null);
    if (p !== null && p !== bridge?.port) setBridgePort(p).then(setBridge, () => setPortError(true));
  };
  const newToken = () =>
    Alert.alert('Make a new pairing token?', 'The Obsidian plugin stops working until you give it the new token.', [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'New token',
        style: 'destructive',
        onPress: () => {
          setCopied(false);
          regenerateBridgeToken().then(setBridge, () => undefined);
        },
      },
    ]);

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
      <View collapsable={false} style={ui.header}>
        <Text style={ui.headerTitle}>Settings</Text>
      </View>

      <Text style={[ui.row, ui.title]}>Voice</Text>
      {engine === null ? (
        <Text style={[ui.row, ui.small]}>Checking the text-to-speech engine...</Text>
      ) : engineBlocked(engine.status) ? (
        <View collapsable={false} style={[ui.card, { borderColor: colors.border }]}>
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
      <View collapsable={false} style={[ui.actions, ui.row]}>
        <Pressable style={ui.button} accessibilityLabel="default slower" onPress={() => changeRate(-1)}>
          <Text style={ui.buttonText}>−</Text>
        </Pressable>
        <Text>{formatRate(rate)}</Text>
        <Pressable style={ui.button} accessibilityLabel="default faster" onPress={() => changeRate(1)}>
          <Text style={ui.buttonText}>+</Text>
        </Pressable>
      </View>

      <Text style={[ui.row, ui.title]}>Obsidian bridge</Text>
      <Text style={[ui.row, ui.small]}>
        Lets the Local TTS Reader plugin in Obsidian on this phone use this phone's voices.
      </Text>
      {bridge === null ? null : (
        <View collapsable={false} style={ui.row}>
          <Text>{bridgeStatus(bridge)}</Text>
          <Pressable
            style={ui.button}
            accessibilityLabel={bridge.enabled ? 'bridge off' : 'bridge on'}
            onPress={() => {
              const on = !bridge.enabled;
              if (!on) {
                setBridgeEnabled(false).then(setBridge, () => undefined);
                return;
              }
              // R-M12: the bridge's notification MUST say it is on, so no permission, no bridge.
              ensureNotifications()
                .then(granted => {
                  setNotifyOff(!granted);
                  setRefused(!granted);
                  return granted ? setBridgeEnabled(true) : null;
                })
                .then(b => b && setBridge(b), () => undefined);
            }}>
            <Text style={ui.buttonText}>{bridge.enabled ? 'Turn off' : 'Turn on'}</Text>
          </Pressable>
          <View collapsable={false} style={ui.actions}>
            <Text style={ui.small}>Port</Text>
            <TextInput
              accessibilityLabel="bridge port"
              style={{ color: colors.text }}
              keyboardType="number-pad"
              value={portText}
              onChangeText={setPortText}
              onEndEditing={applyPort}
              onSubmitEditing={applyPort}
            />
          </View>
          {notifyOff && (bridge.enabled || refused) ? (
            <View collapsable={false}>
              <Text style={ui.small}>
                Read Me needs to show a notification while the bridge is on, and notifications are off
                for Read Me. Allow them in Android settings, then turn the bridge on.
              </Text>
              <Pressable
                style={ui.button}
                accessibilityLabel="open app settings"
                onPress={() => {
                  Linking.openSettings().catch(() => undefined);
                }}>
                <Text style={ui.buttonText}>Open Read Me's settings</Text>
              </Pressable>
            </View>
          ) : null}
          {portError ? <Text style={ui.small}>The port must be a number from 1024 to 65535.</Text> : null}
          {bridge.enabled && bridge.token !== null ? (
            <View collapsable={false}>
              <Text style={ui.small}>Pairing token (paste it into the plugin's settings)</Text>
              <Text selectable accessibilityLabel="pairing token">
                {bridge.token}
              </Text>
              <View collapsable={false} style={ui.actions}>
                <Pressable
                  style={ui.button}
                  accessibilityLabel="copy token"
                  onPress={() => {
                    copyBridgeToken().then(() => setCopied(true), () => undefined);
                  }}>
                  <Text style={ui.buttonText}>{copied ? 'Copied' : 'Copy'}</Text>
                </Pressable>
                <Pressable style={ui.button} accessibilityLabel="new token" onPress={newToken}>
                  <Text style={ui.buttonText}>New token</Text>
                </Pressable>
              </View>
            </View>
          ) : null}
        </View>
      )}

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
