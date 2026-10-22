// R-M05 Trim: tap cuts or restores a paragraph; a long press offers "Cut everything after
// this" and "Start here". Cuts are a separate set; text is never changed (AGENTS.md 12).
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { FlatList, Pressable, View } from 'react-native';
import { ActionSheet } from './ActionSheet';
import { Button } from './Button';
import { ItemUnavailable } from './ItemUnavailable';
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
  // The paragraph whose long-press menu is open (REA-62); null: closed. Only an index, no text.
  const [menuAt, setMenuAt] = useState<number | null>(null);

  // R-M10, REA-50: a rejection is not "deleted". It keeps whatever was loaded (a failed reload
  // must not blank the text or turn a deleted item into an error); with nothing loaded it shows
  // its own state with Retry. No log: the rejection carries only an exception class anyway.
  const [failed, setFailed] = useState(false);
  const load = useCallback(() => {
    getItem(id).then(
      d => {
        setDetail(d);
        setFailed(false);
      },
      () => setFailed(true),
    );
  }, [id]);
  const retry = () => {
    setFailed(false);
    load();
  };

  useEffect(() => {
    load();
    return onItemsChanged(load);
  }, [load]);

  // Nothing, not an empty View, while loading: an empty layout-only View followed by the
  // full screen inside App's former keyed wrapper view made Fabric add children to a view it had not
  // created ("Unable to find viewState ... for addViewAt"), leaving a blank screen on about
  // half of List-to-Trim taps (repro loop, 2026-10-02); with null it opened 10 of 10.
  if (detail === undefined && !failed) return null;
  if (detail === undefined) return <ItemUnavailable kind="failed" onRetry={retry} onBack={onGone} />;
  if (detail === null) return <ItemUnavailable kind="deleted" onBack={onGone} />;

  const count = detail.paragraphs.length;
  const shown = cuts ?? new Set(detail.cuts);
  const current = () => latest.current ?? new Set(detail.cuts);
  const apply = (next: Set<number>) => {
    latest.current = next;
    setLocalCuts(next);
    applyCuts(id, next).catch(() => undefined);
  };
  const choose = (act: (cuts: Set<number>, i: number, count: number) => Set<number>) => {
    const i = menuAt;
    setMenuAt(null);
    if (i !== null) apply(act(current(), i, count));
  };
  const menu = (i: number) => setMenuAt(i);

  return (
    <View collapsable={false} style={ui.screen}>
      <View collapsable={false} style={ui.header}>
        <Text style={ui.headerTitle}>Trim</Text>
        <Button appearance="filled" label="Done" accessibilityLabel="trim done" onPress={onDone} />
      </View>
      <Text tone="secondary" style={[ui.small, ui.row]}>
        {`${count - shown.size} of ${count} paragraphs kept. Tap to cut or restore; hold for more.`}
      </Text>
      <FlatList
        data={detail.paragraphs.map((p, index) => ({ ...p, index }))}
        keyExtractor={p => String(p.index)}
        renderItem={({ item: p }) => {
          const isCut = shown.has(p.index);
          return (
            // REA-50: no accessibilityLabel, so TalkBack reads the paragraph's own text and the
            // visible "cut". The device scripts find it by testID (resource-id), and the testID
            // is also what installs RN's accessibility delegate that carries the hint.
            <Pressable
              testID={`paragraph ${p.index + 1}${isCut ? ' cut' : ''}`}
              accessibilityHint={isCut ? 'Tap to restore. Hold for more.' : 'Tap to cut. Hold for more.'}
              onPress={() => apply(toggleCut(current(), p.index, count))}
              onLongPress={() => menu(p.index)}>
              <Text tone={isCut ? 'disabled' : 'body'} style={[ui.paragraph, isCut ? ui.cut : null]}>
                {p.text}
              </Text>
              {isCut ? <Text tone="secondary" style={[ui.small, ui.row]}>cut</Text> : null}
            </Pressable>
          );
        }}
      />
      <ActionSheet
        visible={menuAt !== null}
        title="Trim"
        actions={[
          { label: 'Start here', testID: 'trim menu start here', onPress: () => choose(startHere) },
          { label: 'Cut everything after this', testID: 'trim menu cut after', onPress: () => choose(cutAfter) },
        ]}
        onClose={() => setMenuAt(null)}
      />
    </View>
  );
}
