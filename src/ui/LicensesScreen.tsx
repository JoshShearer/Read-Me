// R-M13: Settings > Licenses. Reads the generated asset on open; tap a row for its text.
import React, { useEffect, useState } from 'react';
import { FlatList, Pressable, View } from 'react-native';
import { Text } from './Text';
import { getNotices } from '../library/playback';
import { parseNotices, type Notice } from './model';
import { ui } from './ui';

export function LicensesScreen() {
  const [notices, setNotices] = useState<Notice[] | null>(null);
  const [open, setOpen] = useState<string | null>(null);

  useEffect(() => {
    getNotices().then(j => setNotices(parseNotices(j)), () => setNotices([]));
  }, []);

  return (
    <View collapsable={false} style={ui.screen}>
      <View collapsable={false} style={ui.header}>
        <Text style={ui.headerTitle}>Licenses</Text>
      </View>
      <FlatList
        data={notices ?? []}
        keyExtractor={n => `${n.name}@${n.version}`}
        ListEmptyComponent={<Text style={ui.empty}>{notices === null ? 'Loading...' : 'No notices found.'}</Text>}
        renderItem={({ item: n }) => {
          const key = `${n.name}@${n.version}`;
          return (
            <Pressable style={ui.row} onPress={() => setOpen(o => (o === key ? null : key))}>
              <Text>{`${n.name} ${n.version}`}</Text>
              <Text style={ui.small}>{n.license}</Text>
              {open === key ? <Text style={ui.small}>{n.text ?? n.url ?? ''}</Text> : null}
            </Pressable>
          );
        }}
      />
    </View>
  );
}
