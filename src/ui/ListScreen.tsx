// R-M01 List: unread items or the Archive; R-M10 states with their actions. Never logs.
// REA-72 (ADR 0017): unread rows are a stored queue. REA-73: a long press lifts a row and a drag
// puts it where the finger lets go; the row's accessibility actions (TalkBack) move it without
// dragging. No gesture library: React Native's responder system and Animated (NN 14).
import React, { createContext, useCallback, useContext, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  Animated,
  FlatList,
  Pressable,
  useColorScheme,
  View,
  type GestureResponderEvent,
  type LayoutChangeEvent,
  type StyleProp,
  type ViewStyle,
} from 'react-native';
import { Button } from './Button';
import { Text } from './Text';
import {
  deleteItem,
  drainFetched,
  listItems,
  moveItem,
  onItemsChanged,
  placeItem,
  restoreItem,
  retryFetch,
  type Item,
  type Move,
} from '../library/library';
import { getPlayback, marker, onPlayback, type Playback } from '../library/playback';
import {
  A11Y_MOVE,
  A11Y_NAME,
  ACTION_LABEL,
  MOVE_LABEL,
  actions,
  moves,
  badge,
  dragShift,
  dropBefore,
  dropIndex,
  reordered,
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

// REA-73: the drag as the List's cells see it. Each cell (a row and its divider) lays itself out
// into [onCellLayout], and moves by [dy] when it is the dragged one or by [shift] otherwise.
type DragView = {
  dragId: number | null;
  dy: Animated.Value;
  shift: (id: number) => number;
  onCellLayout: (id: number, y: number, height: number) => void;
};
const DragContext = createContext<DragView | null>(null);

// A cell eases aside as the dragged row passes it. When the drag ends it snaps back in the same
// render that lays the List out in its new order; easing then would slide it from its old offset.
function useEased(to: number, animate: boolean): Animated.Value {
  const v = useRef(new Animated.Value(to)).current;
  // Before paint, so the snap and the new layout land in the same frame.
  useLayoutEffect(() => {
    if (animate) Animated.timing(v, { toValue: to, duration: 120, useNativeDriver: true }).start();
    else v.setValue(to);
  }, [to, animate, v]);
  return v;
}

// FlatList's CellRendererComponent: outside ListScreen so it never remounts (ui.ts, #58265).
function DragCell({
  item,
  style,
  onLayout,
  children,
}: {
  item: Item;
  style?: StyleProp<ViewStyle>;
  onLayout?: (e: LayoutChangeEvent) => void;
  children?: React.ReactNode;
}) {
  const drag = useContext(DragContext);
  const colors = palette(useColorScheme());
  const lifted = drag?.dragId === item.id;
  const eased = useEased(drag && !lifted ? drag.shift(item.id) : 0, drag?.dragId != null);
  return (
    <Animated.View
      collapsable={false}
      style={[
        style,
        lifted
          ? [lift, { backgroundColor: colors.surfaceContainerHigh, transform: [{ translateY: drag.dy }] }]
          : { transform: [{ translateY: eased }] },
      ]}
      onLayout={e => {
        onLayout?.(e);
        drag?.onCellLayout(item.id, e.nativeEvent.layout.y, e.nativeEvent.layout.height);
      }}>
      {children}
    </Animated.View>
  );
}

const lift = { zIndex: 10, elevation: 8 } as const;

// Within this far of the List's top or bottom edge a dragged row scrolls the List, faster nearer.
const EDGE = 72;
const MAX_STEP = 14;

type Drag = { id: number; from: number; height: number; center: number; pageY: number; scrollY: number };

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
  // REA-73: the row being dragged (state, for rendering) and where it would land.
  const [drag, setDrag] = useState<Drag | null>(null);
  const [dropAt, setDropAt] = useState(-1);
  const dragRef = useRef<Drag | null>(null);
  const dropRef = useRef(-1);
  const granted = useRef(false);
  const dy = useRef(new Animated.Value(0)).current;
  const cells = useRef(new Map<number, { y: number; height: number }>()).current;
  const listRef = useRef<FlatList<Item>>(null);
  const listFrame = useRef({ pageY: 0, height: 0 });
  const listBox = useRef<React.ComponentRef<typeof View>>(null);
  const scrollY = useRef(0);
  const contentHeight = useRef(0);
  const fingerY = useRef(0);
  const frame = useRef<number | null>(null);

  const refresh = useCallback(() => {
    listItems().then(setItems, () => setItems([]));
  }, []);

  useEffect(() => {
    refresh();
    // Change events are drained by App's watchFetched; a drain here on mount retries an item
    // whose extraction failed when the user comes back to the List (REA-50).
    drainFetched().catch(() => undefined);
    return onItemsChanged(refresh);
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

  const move = (item: Item, to: Move) => {
    // Relative to the stored order at the write (ADR 0017); the List refreshes on the change.
    // A rejected move fires no change, so reload to show the order that is actually stored.
    moveItem(item.id, to).catch(refresh);
  };

  const shown = visibleItems(items, archive);
  const shownRef = useRef(shown);
  shownRef.current = shown;

  // The dragged row's offset from where it rests, and the index it would land at.
  const track = useCallback(() => {
    const d = dragRef.current;
    if (!d) return;
    const offset = fingerY.current - d.pageY + (scrollY.current - d.scrollY);
    dy.setValue(offset);
    const centers = shownRef.current.map(i => {
      const c = cells.get(i.id);
      return c ? c.y + c.height / 2 : undefined;
    });
    const to = dropIndex(centers, d.from, d.center + offset);
    if (to !== dropRef.current) {
      dropRef.current = to;
      setDropAt(to);
    }
  }, [cells, dy]);

  // Scrolls the List while the finger is near an edge, one step per frame.
  const edgeScroll = useCallback(() => {
    frame.current = null;
    if (!dragRef.current) return;
    const { pageY, height } = listFrame.current;
    if (height <= 0) return; // not measured yet
    const fromTop = fingerY.current - pageY;
    const fromBottom = pageY + height - fingerY.current;
    let step = 0;
    if (fromTop < EDGE) step = -MAX_STEP * Math.min(1, (EDGE - fromTop) / EDGE);
    else if (fromBottom < EDGE) step = MAX_STEP * Math.min(1, (EDGE - fromBottom) / EDGE);
    const max = Math.max(0, contentHeight.current - height);
    const next = Math.min(max, Math.max(0, scrollY.current + step));
    if (next !== scrollY.current) {
      scrollY.current = next;
      listRef.current?.scrollToOffset({ offset: next, animated: false });
      track();
    }
    if (step !== 0) frame.current = requestAnimationFrame(edgeScroll);
  }, [track]);

  const endDrag = useCallback(
    (commit: boolean) => {
      const d = dragRef.current;
      if (frame.current !== null) cancelAnimationFrame(frame.current);
      frame.current = null;
      dragRef.current = null;
      granted.current = false;
      const to = dropRef.current;
      dropRef.current = -1;
      setDrag(null);
      setDropAt(-1);
      dy.setValue(0);
      if (!d || !commit) return;
      const rows = shownRef.current;
      const from = rows.findIndex(i => i.id === d.id);
      const before = from < 0 ? undefined : dropBefore(rows.map(i => i.id), from, to);
      if (before === undefined) return;
      // Shown at once; the store's change event then refreshes from what was stored. A refused
      // place fires no event, so reload to show the order that is actually stored.
      const order = reordered(rows, from, to);
      setItems(all => [...order, ...all.filter(i => i.archivedAt !== undefined)]);
      placeItem(d.id, before).then(ok => (ok ? undefined : refresh()), refresh);
    },
    [dy, refresh],
  );

  const startDrag = (item: Item, index: number, e: GestureResponderEvent) => {
    const c = cells.get(item.id);
    if (archive || shown.length < 2 || !c) return;
    const d = {
      id: item.id,
      from: index,
      height: c.height,
      center: c.y + c.height / 2,
      pageY: e.nativeEvent.pageY,
      scrollY: scrollY.current,
    };
    dragRef.current = d;
    dropRef.current = index;
    fingerY.current = e.nativeEvent.pageY;
    granted.current = false;
    dy.setValue(0);
    setDrag(d);
    setDropAt(index);
    listBox.current?.measureInWindow((_x: number, y: number, _w: number, h: number) => {
      listFrame.current = { pageY: y, height: h };
    });
  };

  // Leaving the unread List mid-drag (the Archive shown, the row gone) drops nothing.
  useEffect(() => {
    if (drag && (archive || !shown.some(i => i.id === drag.id))) endDrag(false);
  }, [archive, drag, shown, endDrag]);
  useEffect(() => () => {
    if (frame.current !== null) cancelAnimationFrame(frame.current);
  }, []);

  const dragView = useMemo<DragView>(
    () => ({
      dragId: drag?.id ?? null,
      dy,
      shift: id => {
        if (!drag) return 0;
        const i = shown.findIndex(r => r.id === id);
        return i < 0 ? 0 : dragShift(i, drag.from, dropAt, drag.height);
      },
      onCellLayout: (id, y, height) => cells.set(id, { y, height }),
    }),
    [drag, dropAt, dy, shown, cells],
  );

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
      <View
        ref={listBox}
        testID="list surface"
        collapsable={false}
        style={ui.screen}
        // REA-73: once a long press has lifted a row, the List takes the touch from the row's
        // Pressable at the first move and keeps it until the finger lifts.
        onStartShouldSetResponderCapture={() => dragRef.current !== null}
        onMoveShouldSetResponderCapture={() => dragRef.current !== null}
        onResponderGrant={() => {
          granted.current = true;
        }}
        onResponderTerminationRequest={() => dragRef.current === null}
        onResponderMove={e => {
          fingerY.current = e.nativeEvent.pageY;
          track();
          if (frame.current === null) frame.current = requestAnimationFrame(edgeScroll);
        }}
        onResponderRelease={() => endDrag(true)}
        onResponderTerminate={() => endDrag(false)}>
      <DragContext.Provider value={dragView}>
      <FlatList
        ref={listRef}
        data={shown}
        extraData={dragView}
        CellRendererComponent={DragCell}
        scrollEnabled={drag === null}
        onScroll={e => {
          scrollY.current = e.nativeEvent.contentOffset.y;
        }}
        scrollEventThrottle={16}
        onContentSizeChange={(_w, h) => {
          contentHeight.current = h;
        }}
        keyExtractor={item => String(item.id)}
        ItemSeparatorComponent={Divider}
        ListEmptyComponent={
          <Text style={ui.empty}>
            {archive ? 'Nothing archived yet.' : 'In any app, tap Share and pick Read Me Offline to add a link or text.'}
          </Text>
        }
        renderItem={({ item, index }) => {
          const acts = actions(item);
          const rowMoves = archive ? [] : moves(index, shown.length);
          const mark = marker(item, playback);
          const sub = subtitle(item);
          const help = guidance(item);
          return (
            <Pressable
              testID={`row ${item.title}`}
              style={ui.entry}
              accessibilityActions={rowMoves.map(m => ({ name: A11Y_NAME[m], label: MOVE_LABEL[m] }))}
              onAccessibilityAction={e => {
                const to = A11Y_MOVE[e.nativeEvent.actionName];
                if (to && rowMoves.includes(to)) move(item, to);
              }}
              onPress={() => {
                if (acts.includes('open')) onOpen(item);
              }}
              delayLongPress={350}
              onLongPress={rowMoves.length > 0 ? e => startDrag(item, index, e) : undefined}
              onPressOut={() => {
                // A long press released without a move never reaches the List's responder: the
                // row keeps the touch, so end the lift here. A move hands the touch to the List
                // (grant before this row's termination), and the List ends the drag instead.
                setTimeout(() => {
                  if (dragRef.current?.id === item.id && !granted.current) endDrag(false);
                }, 0);
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
      </DragContext.Provider>
      </View>
    </View>
  );
}
