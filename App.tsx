// R-M01: four screens, opening on the list. A route stack plus the hardware back button;
// four routes do not need a navigation library (AGENTS.md 14 keeps dependencies minimal).
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { AppState, BackHandler, StatusBar, useColorScheme, View } from 'react-native';
import { SafeAreaProvider, useSafeAreaInsets } from 'react-native-safe-area-context';
import {
  getItem,
  markOpened,
  onSharedOpen,
  takeSharedOpen,
  watchFetched,
  type Item,
} from './src/library/library';
import { getPlayback, handedOver, onPlayback, type Playback } from './src/library/playback';
import { LicensesScreen } from './src/ui/LicensesScreen';
import { ListScreen } from './src/ui/ListScreen';
import { ReaderScreen } from './src/ui/ReaderScreen';
import { SettingsScreen } from './src/ui/SettingsScreen';
import { TrimScreen } from './src/ui/TrimScreen';
import {
  back,
  followHandover,
  openRoute,
  push,
  sharedOpen,
  trimDone,
  type Route,
} from './src/ui/model';
import { palette, statusBarStyle } from './src/ui/theme';
import { ui } from './src/ui/ui';

function Main() {
  const insets = useSafeAreaInsets();
  const scheme = useColorScheme();
  const [stack, setStack] = useState<Route[]>([{ name: 'list' }]);
  // REA-63: bumped by a shared open, so the List remounts on its unread tab (the tab is the
  // List's own state).
  const [listEpoch, setListEpoch] = useState(0);
  const top = stack[stack.length - 1];

  // ADR 0007's drain runs whatever screen is open (REA-50).
  useEffect(() => watchFetched(), []);

  useEffect(() => {
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      const next = back(stack);
      if (next === null) return false;
      setStack(next);
      return true;
    });
    return () => sub.remove();
  }, [stack]);

  const go = useCallback((r: Route) => setStack(s => push(s, r)), []);
  const toList = useCallback(() => setStack([{ name: 'list' }]), []);
  const open = useCallback(
    (item: Item) => {
      go(openRoute(item));
      markOpened(item.id).catch(() => undefined);
    },
    [go],
  );

  // R-S05, REA-55 (ADR 0011): when the service hands over to the next item, a Reader showing the
  // finished one follows it. This lives here, not in the Reader, because the Reader unmounts
  // under Trim, and a handover then must still move it. It only changes a route: no play,
  // pause or resume (AGENTS.md 11), and no markOpened (following is not an open).
  const lastSeen = useRef<Playback | null>(null);
  useEffect(() => {
    let live = true;
    let seq = 0; // events seen; a getPlayback answer older than an event is dropped
    const see = (p: Playback) => {
      const h = handedOver(lastSeen.current, p);
      lastSeen.current = p;
      if (h) setStack(s => followHandover(s, h.from, h.to));
    };
    // On mount and on return to the foreground: handovers whose events never arrived are
    // followed from the service's state (A then C follows A to C).
    const refresh = () => {
      const at = seq;
      getPlayback().then(
        p => {
          if (live && at === seq) see(p);
        },
        () => undefined,
      );
    };
    const off = onPlayback(p => {
      seq++;
      see(p);
    });
    refresh();
    const app = AppState.addEventListener('change', s => {
      if (s === 'active') refresh();
    });
    return () => {
      live = false;
      off();
      app.remove();
    };
  }, []);

  // REA-63 (ADR 0016): a share matched an unread item. Taken on mount (a cold start from the
  // share), on return to the foreground, and on the event when Read Me is already in front. It
  // only replaces the route stack and records the open; no play, pause or resume (AGENTS.md 11).
  useEffect(() => {
    let live = true;
    const consume = () => {
      // Through Promise.resolve, so a synchronous throw from the native call is caught too.
      Promise.resolve()
        .then(() => takeSharedOpen())
        .then(async id => {
          if (id === null || id === undefined || !live) return;
          const detail = await getItem(id).catch(() => null);
          if (!live) return;
          const next = sharedOpen(detail?.item ?? null);
          setStack(next.stack);
          setListEpoch(e => e + 1);
          if (next.markOpened) markOpened(id).catch(() => undefined);
        })
        .catch(() => undefined);
    };
    const off = onSharedOpen(consume);
    consume();
    const app = AppState.addEventListener('change', s => {
      if (s === 'active') consume();
    });
    return () => {
      live = false;
      off();
      app.remove();
    };
  }, []);

  let screen: React.ReactElement;
  switch (top.name) {
    case 'list':
      screen = <ListScreen onOpen={open} onSettings={() => go({ name: 'settings' })} />;
      break;
    case 'trim':
      screen = (
        <TrimScreen id={top.id} onDone={() => setStack(s => trimDone(s, top.id))} onGone={toList} />
      );
      break;
    case 'reader':
      screen = <ReaderScreen id={top.id} onTrim={() => go({ name: 'trim', id: top.id })} onGone={toList} />;
      break;
    case 'settings':
      screen = <SettingsScreen onLicenses={() => go({ name: 'licenses' })} />;
      break;
    case 'licenses':
      screen = <LicensesScreen />;
      break;
    default:
      screen = <ListScreen onOpen={open} onSettings={() => go({ name: 'settings' })} />;
  }
  // The screen is keyed by route, with no wrapper view: each screen's own root View
  // (collapsable={false}) is the one native view a switch removes and inserts. Phase 4 keyed a
  // wrapper View instead (an unkeyed shared parent crashed List-to-Trim with "addViewAt: failed
  // to insert view", 2026-10-02). But that wrapper is created empty while the next screen loads
  // (screens render null until their data arrives), and Fabric then lost it before the screen
  // was inserted ("Unable to find viewState for tag ... for addViewAt", a blank screen): Trim's
  // Done on a long article blanked the Reader in 4-5 of 10 runs, 10 of 10 with view
  // preallocation off. Keyed screens: 0 of 20 (REA-26, 2026-10-03; npm run device:screens).
  const key = 'id' in top ? `${top.name}:${top.id}` : top.name === 'list' ? `list:${listEpoch}` : top.name;
  // REA-22, REA-24: the status bar sits on this surface, so its icons follow the mode too.
  // REA-28: the bottom inset too, or the Reader's transport sits under the gesture bar (Play's
  // 48 dp target reached y 2962 of 2992 on the reference device, the home indicator across it).
  return (
    <View
      collapsable={false}
      style={[ui.screen, { paddingTop: insets.top, paddingBottom: insets.bottom, backgroundColor: palette(scheme).surface }]}>
      <StatusBar barStyle={statusBarStyle(scheme)} />
      <React.Fragment key={key}>{screen}</React.Fragment>
    </View>
  );
}

export default function App() {
  return (
    <SafeAreaProvider>
      <Main />
    </SafeAreaProvider>
  );
}
