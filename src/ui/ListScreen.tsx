// R-M01 List: unread items or the Archive; R-M10 states with their actions. Never logs.
import React, { useCallback, useEffect, useState } from 'react';
import { Alert, FlatList, Pressable, Text, View } from 'react-native';
import {
  deleteItem,
  drainFetched,
  listItems,
  onItemsChanged,
  restoreItem,
  retryFetch,
  type Item,
} from '../library/library';
import { getPlayback, marker, onPlayback, type Playback } from '../library/playback';
import {
  ACTION_LABEL,
  actions,
  badge,
  guidance,
  subtitle,
  visibleItems,
  type Action,
} from './model';
import { ui } from './ui';

export function ListScreen({
  onOpen,
  onSettings,
}: {
  onOpen: (item: Item) => void;
  onSettings: () => void;
}) {
  const [items, setItems] = useState<Item[]>([]);
  const [archive, setArchive] = useState(false);
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

  const run = (a: Action, item: Item) => {
    if (a === 'open') onOpen(item);
    else if (a === 'retry') retryFetch(item.id).catch(() => undefined);
    else if (a === 'restore') restoreItem(item.id).catch(() => undefined);
    else {
      Alert.alert('Delete this item?', 'Its text is removed from this phone.', [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Delete',
          style: 'destructive',
          onPress: () => {
            deleteItem(item.id).catch(() => undefined);
          },
        },
      ]);
    }
  };

  const shown = visibleItems(items, archive);
  return (
    <View style={ui.screen}>
      <View style={ui.header}>
        <Text style={ui.headerTitle}>{archive ? 'Archive' : 'Read Me'}</Text>
        <Pressable
          style={ui.button}
          accessibilityLabel={archive ? 'unread' : 'archive'}
          onPress={() => setArchive(a => !a)}>
          <Text style={ui.buttonText}>{archive ? 'Unread' : 'Archive'}</Text>
        </Pressable>
        <Pressable style={ui.button} accessibilityLabel="settings" onPress={onSettings}>
          <Text style={ui.buttonText}>Settings</Text>
        </Pressable>
      </View>
      <FlatList
        data={shown}
        keyExtractor={item => String(item.id)}
        ListEmptyComponent={
          <Text style={ui.empty}>
            {archive ? 'Nothing archived yet.' : 'Share a link or text to Read Me.'}
          </Text>
        }
        renderItem={({ item }) => {
          const acts = actions(item);
          const mark = marker(item, playback);
          const sub = subtitle(item);
          const help = guidance(item);
          return (
            <Pressable
              style={ui.row}
              onPress={() => {
                if (acts.includes('open')) onOpen(item);
              }}>
              <Text style={ui.title}>{item.title}</Text>
              {sub ? <Text style={ui.small}>{sub}</Text> : null}
              <Text style={ui.small}>{badge(item)}</Text>
              {mark && mark !== 'archived' ? <Text style={ui.small}>{mark}</Text> : null}
              {help ? <Text style={ui.small}>{help}</Text> : null}
              <View style={ui.actions}>
                {acts
                  .filter(a => a !== 'open' || item.state === 'extract-poor' || archive)
                  .map(a => (
                    <Pressable
                      key={a}
                      style={ui.button}
                      accessibilityLabel={`${a} ${item.title}`}
                      onPress={() => run(a, item)}>
                      <Text style={ui.buttonText}>
                        {a === 'open' && item.state === 'extract-poor' ? 'Read anyway' : ACTION_LABEL[a]}
                      </Text>
                    </Pressable>
                  ))}
              </View>
            </Pressable>
          );
        }}
      />
    </View>
  );
}
