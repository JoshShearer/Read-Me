// R-M07 Reader: the kept text, the current sentence highlighted and kept in view, and the
// transport. A view of PlaybackService (AGENTS.md 11): every button sends a command; the
// highlight follows the service's events. R-M10: a blocking card when there is no engine or
// no offline voice, checked on open rather than after a failed play (REA-18).
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { FlatList, Linking, useColorScheme, View } from 'react-native';
import { Button, IconButton } from './Button';
import { Stepper } from './Stepper';
import { Text } from './Text';
import { palette } from './theme';
import { getItem, onItemsChanged, type ItemDetail } from '../library/library';
import {
  backParagraph,
  engineBlocked,
  getEngine,
  getPlayback,
  next,
  onPlayback,
  previous,
  setRate,
  toggle,
  type Playback,
} from '../library/playback';
import Native from '../native/NativeReadMeSpeech';
import { formatRate, lineTop, RATE_MAX, RATE_MIN, readerParagraphs, stepRate, type ReaderParagraph } from './model';
import { ui } from './ui';

/** Space kept above the highlighted line when scrolling to it, in dp. */
const KEEP_ABOVE = 120;

export function ReaderScreen({
  id,
  onTrim,
  onGone,
}: {
  id: number;
  onTrim: () => void;
  onGone: () => void;
}) {
  const colors = palette(useColorScheme());
  const [detail, setDetail] = useState<ItemDetail | null | undefined>(undefined);
  const [playback, setPlayback] = useState<Playback | null>(null);
  const [engine, setEngine] = useState<string | undefined>(undefined);
  // The saved rate: the service reports it while it holds an item; otherwise getRate.
  const [rate, setRateState] = useState(2);
  const list = useRef<FlatList<ReaderParagraph>>(null);
  // The current paragraph's laid-out lines, so a long paragraph scrolls to the sentence, not
  // only to its first line (R-M07 "kept in view"; /critique run A F1).
  const [lines, setLines] = useState<{ index: number; lines: { text: string; y: number }[] } | null>(null);

  const load = useCallback(() => {
    getItem(id).then(setDetail, () => setDetail(null));
  }, [id]);

  useEffect(() => {
    load();
    return onItemsChanged(load);
  }, [load]);

  useEffect(() => {
    getPlayback().then(setPlayback, () => undefined);
    getEngine().then(e => setEngine(e.status), () => setEngine('no-engine'));
    Native.getRate().then(setRateState, () => undefined);
    return onPlayback(p => {
      setPlayback(p);
      if (p.itemId !== null) setRateState(p.rate);
      if (engineBlocked(p.engine)) setEngine(p.engine);
    });
  }, []);

  const mine = playback?.itemId === id ? playback : null;
  const paragraphs = detail ? readerParagraphs(detail.paragraphs, detail.cuts, mine?.sentence ?? null) : [];
  const currentAt = paragraphs.findIndex(p => p.current !== null);

  const current = currentAt >= 0 ? paragraphs[currentAt] : null;
  const sentenceStart = current?.current?.start ?? -1;
  const lineY = current && lines?.index === current.index ? lineTop(lines.lines, sentenceStart) : 0;

  useEffect(() => {
    if (currentAt < 0) return;
    // viewOffset moves the target down by the line's depth in the paragraph, leaving
    // KEEP_ABOVE of context above the highlighted line.
    list.current?.scrollToIndex({ index: currentAt, viewPosition: 0, viewOffset: KEEP_ABOVE - lineY, animated: true });
  }, [currentAt, sentenceStart, lineY]);

  // Nothing, not an empty View, while loading: an empty layout-only View followed by the
  // full screen inside App's former keyed wrapper view made Fabric add children to a view it had not
  // created ("Unable to find viewState ... for addViewAt"), leaving a blank screen on about
  // half of List-to-Trim taps (repro loop, 2026-10-02); with null it opened 10 of 10.
  if (detail === undefined) return null;
  if (detail === null) {
    return (
      <View collapsable={false} style={ui.screen}>
        <Text style={ui.empty}>This item was deleted.</Text>
        <Button label="Back to the list" style={ui.start} onPress={onGone} />
      </View>
    );
  }

  const header = (
    <View collapsable={false} style={ui.header}>
      <Text style={ui.itemTitle} numberOfLines={2}>
        {detail.item.title}
      </Text>
      <Button label="Trim" accessibilityLabel="trim" onPress={onTrim} />
    </View>
  );

  if (engineBlocked(engine)) {
    return (
      <View collapsable={false} style={ui.screen}>
        {header}
        <View collapsable={false} style={[ui.card, { backgroundColor: colors.surfaceContainerLow, borderColor: colors.outlineVariant }]}>
          <Text>
            This phone has no offline text-to-speech voice Read Me can use, so it cannot read aloud.
            Install or enable an offline voice in Android's text-to-speech settings.
          </Text>
          <Button
            appearance="filled"
            label="Open text-to-speech settings"
            accessibilityLabel="open tts settings"
            style={ui.start}
            onPress={() => {
              Linking.sendIntent('com.android.settings.TTS_SETTINGS').catch(() => undefined);
            }}
          />
        </View>
      </View>
    );
  }

  if (paragraphs.length === 0) {
    return (
      <View collapsable={false} style={ui.screen}>
        {header}
        <Text style={ui.empty}>Everything in this item is cut. Open Trim to keep some of it.</Text>
      </View>
    );
  }

  const changeRate = (dir: 1 | -1) => {
    setRate(stepRate(rate, dir)).then(setRateState, () => undefined);
  };
  const control = (fn: () => Promise<unknown>) => () => {
    fn().catch(() => undefined);
  };

  return (
    <View collapsable={false} style={ui.screen}>
      {header}
      <FlatList
        ref={list}
        data={paragraphs}
        keyExtractor={p => String(p.index)}
        onScrollToIndexFailed={info => {
          // Not measured yet: jump near it, then retry once the cells around it have rendered.
          list.current?.scrollToOffset({ offset: info.averageItemLength * info.index, animated: false });
          setTimeout(() => {
            list.current?.scrollToIndex({ index: info.index, viewPosition: 0, viewOffset: KEEP_ABOVE, animated: true });
          }, 100);
        }}
        renderItem={({ item: p }) =>
          p.current ? (
            <View collapsable={false} accessibilityLabel="current paragraph">
              <Text
                style={ui.paragraph}
                onTextLayout={e =>
                  setLines({ index: p.index, lines: e.nativeEvent.lines.map(l => ({ text: l.text, y: l.y })) })
                }>
                {p.text.slice(0, p.current.start)}
                <Text style={{ backgroundColor: colors.primaryContainer, color: colors.onPrimaryContainer }}>{p.text.slice(p.current.start, p.current.end)}</Text>
                {p.text.slice(p.current.end)}
              </Text>
            </View>
          ) : (
            <Text style={ui.paragraph}>{p.text}</Text>
          )
        }
      />
      <View
        collapsable={false}
        style={[ui.dock, { backgroundColor: colors.surfaceContainerLow, borderColor: colors.outlineVariant }]}>
        {/* Sentence controls act on whatever the service holds, so they work only for this
            item; they stay in place, disabled, so Play/Pause never moves. */}
        <IconButton glyph="¶◀" accessibilityLabel="back paragraph" disabled={!mine} onPress={control(backParagraph)} />
        <IconButton glyph="◀" accessibilityLabel="previous sentence" disabled={!mine} onPress={control(previous)} />
        {/* Wide enough for "Pause", so the buttons beside it do not move when it changes. */}
        <Button
          appearance="filled"
          label={mine?.playing ? 'Pause' : 'Play'}
          accessibilityLabel={mine?.playing ? 'pause' : 'play'}
          style={ui.play}
          onPress={control(() => toggle(id, playback))}
        />
        <IconButton glyph="▶" accessibilityLabel="next sentence" disabled={!mine} onPress={control(next)} />
        <Stepper
          value={rate}
          min={RATE_MIN}
          max={RATE_MAX}
          format={formatRate}
          onStep={changeRate}
          decreaseLabel="slower"
          increaseLabel="faster"
        />
      </View>
    </View>
  );
}
