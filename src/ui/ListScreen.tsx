// R-M01 List: unread items or the Archive; R-M10 states with their actions. Never logs.
import React, { useCallback, useEffect, useState } from 'react';
import { Alert, FlatList, Pressable, useColorScheme, View } from 'react-native';
import { Button } from './Button';
import { Text } from './Text';
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
import { palette } from './theme';
import { ui } from './ui';

// A hairline between items. Defined once, outside ListScreen: a component created during
// render remounts on every render, the view churn react/react-native#58265 punishes (ui.ts).
function Divider() {
  const colors = palette(useColorScheme());
  return <View collapsable={false} style={[ui.divider, { backgroundColor: colors.outlineVariant }]} />;
}

export function ListScreen({
  onOpen,
  onSettings,
}: {
  onOpen: (item: Item) => void;
  onSettings: () => void;
}) {
  const colors = palette(useColorScheme());
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
    <View collapsable={false} style={ui.screen}>
      <View collapsable={false} style={ui.header}>
        <Text style={ui.headerTitle}>{archive ? 'Archive' : 'Read Me'}</Text>
        <Button
          label={archive ? 'Unread' : 'Archive'}
          accessibilityLabel={archive ? 'unread' : 'archive'}
          onPress={() => setArchive(a => !a)}
        />
        <Button label="Settings" accessibilityLabel="settings" onPress={onSettings} />
      </View>
      <FlatList
        data={shown}
        keyExtractor={item => String(item.id)}
        ItemSeparatorComponent={Divider}
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
              style={ui.entry}
              onPress={() => {
                if (acts.includes('open')) onOpen(item);
              }}>
              <Text style={ui.entryTitle} numberOfLines={3}>{item.title}</Text>
              {sub ? <Text tone="secondary" style={ui.meta}>{sub}</Text> : null}
              <Text tone={item.state === 'fetch-failed' ? 'error' : 'secondary'} style={ui.meta}>{badge(item)}</Text>
              {mark && mark !== 'archived' ? <Text tone="secondary" style={ui.meta}>{mark}</Text> : null}
              {help ? <Text tone="secondary" style={ui.small}>{help}</Text> : null}
              {item.progress > 0 && item.progress < 1 ? (
                <View
                  collapsable={false}
                  accessibilityElementsHidden
                  importantForAccessibility="no-hide-descendants"
                  style={[ui.track, { backgroundColor: colors.surfaceVariant }]}>
                  <View
                    collapsable={false}
                    style={[ui.fill, { width: `${Math.round(item.progress * 100)}%`, backgroundColor: colors.primary }]}
                  />
                </View>
              ) : null}
              <View collapsable={false} style={ui.actions}>
                {acts
                  .filter(a => a !== 'open' || item.state === 'extract-poor' || archive)
                  .map(a => (
                    <Button
                      key={a}
                      appearance={a === 'delete' ? 'quiet' : 'tonal'}
                      label={a === 'open' && item.state === 'extract-poor' ? 'Read anyway' : ACTION_LABEL[a]}
                      accessibilityLabel={`${a} ${item.title}`}
                      onPress={() => run(a, item)}
                    />
                  ))}
              </View>
            </Pressable>
          );
        }}
      />
    </View>
  );
}
