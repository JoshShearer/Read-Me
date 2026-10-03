// R-M05 Trim: tap cuts or restores a paragraph; a long press offers "Cut everything after
// this" and "Start here". Cuts are a separate set; text is never changed (AGENTS.md 12).
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, FlatList, Pressable, View } from 'react-native';
import { Text } from './Text';
import { getItem, onItemsChanged, type ItemDetail } from '../library/library';
import { applyCuts } from '../library/playback';
import { cutAfter, startHere, toggleCut } from '../trim/cuts';
import { ui } from './ui';

export function TrimScreen({
  id,
  onDone,
  onGone,
}: {
  id: number;
  onDone: () => void;
  onGone: () => void;
}) {
  // undefined: loading; null: the item is gone (Review Focus 2).
  const [detail, setDetail] = useState<ItemDetail | null | undefined>(undefined);
  // The cut set this screen last wrote. Taps build on it, not on `detail`, which only catches
  // up after the store's change event and a reload: two quick taps would otherwise each start
  // from the old set and the second write would undo the first (final review Important 2).
  const latest = useRef<Set<number> | null>(null);
  const [cuts, setLocalCuts] = useState<Set<number> | null>(null);

  const load = useCallback(() => {
    getItem(id).then(setDetail, () => setDetail(null));
  }, [id]);

  useEffect(() => {
    load();
    return onItemsChanged(load);
  }, [load]);

  // Nothing, not an empty View, while loading: an empty layout-only View followed by the
  // full screen inside App's keyed screen view made Fabric add children to a view it had not
  // created ("Unable to find viewState ... for addViewAt"), leaving a blank screen on about
  // half of List-to-Trim taps (repro loop, 2026-10-02); with null it opened 10 of 10.
  if (detail === undefined) return null;
  if (detail === null) {
    return (
      <View collapsable={false} style={ui.screen}>
        <Text style={ui.empty}>This item was deleted.</Text>
        <Pressable style={ui.button} onPress={onGone}>
          <Text style={ui.buttonText}>Back to the list</Text>
        </Pressable>
      </View>
    );
  }

  const count = detail.paragraphs.length;
  const shown = cuts ?? new Set(detail.cuts);
  const current = () => latest.current ?? new Set(detail.cuts);
  const apply = (next: Set<number>) => {
    latest.current = next;
    setLocalCuts(next);
    applyCuts(id, next).catch(() => undefined);
  };
  const menu = (i: number) =>
    Alert.alert('Trim', undefined, [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Start here', onPress: () => apply(startHere(current(), i, count)) },
      { text: 'Cut everything after this', onPress: () => apply(cutAfter(current(), i, count)) },
    ]);

  return (
    <View collapsable={false} style={ui.screen}>
      <View collapsable={false} style={ui.header}>
        <Text style={ui.headerTitle}>Trim</Text>
        <Pressable style={ui.button} accessibilityLabel="trim done" onPress={onDone}>
          <Text style={ui.buttonText}>Done</Text>
        </Pressable>
      </View>
      <Text style={[ui.small, ui.row]}>
        {`${count - shown.size} of ${count} paragraphs kept. Tap to cut or restore; hold for more.`}
      </Text>
      <FlatList
        data={detail.paragraphs.map((p, index) => ({ ...p, index }))}
        keyExtractor={p => String(p.index)}
        renderItem={({ item: p }) => {
          const isCut = shown.has(p.index);
          return (
            <Pressable
              accessibilityLabel={`paragraph ${p.index + 1}${isCut ? ' cut' : ''}`}
              onPress={() => apply(toggleCut(current(), p.index, count))}
              onLongPress={() => menu(p.index)}>
              <Text style={[ui.paragraph, isCut ? ui.cut : null]}>{p.text}</Text>
              {isCut ? <Text style={[ui.small, ui.row]}>cut</Text> : null}
            </Pressable>
          );
        }}
      />
    </View>
  );
}
