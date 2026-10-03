// R-M01: four screens, opening on the list. A route stack plus the hardware back button;
// four routes do not need a navigation library (AGENTS.md 14 keeps dependencies minimal).
import React, { useCallback, useEffect, useState } from 'react';
import { BackHandler, StatusBar, useColorScheme, View } from 'react-native';
import { SafeAreaProvider, useSafeAreaInsets } from 'react-native-safe-area-context';
import { markOpened, type Item } from './src/library/library';
import { LicensesScreen } from './src/ui/LicensesScreen';
import { ListScreen } from './src/ui/ListScreen';
import { ReaderScreen } from './src/ui/ReaderScreen';
import { SettingsScreen } from './src/ui/SettingsScreen';
import { TrimScreen } from './src/ui/TrimScreen';
import { back, openRoute, push, trimDone, type Route } from './src/ui/model';
import { palette, statusBarStyle } from './src/ui/theme';
import { ui } from './src/ui/ui';

function Main() {
  const insets = useSafeAreaInsets();
  const scheme = useColorScheme();
  const [stack, setStack] = useState<Route[]>([{ name: 'list' }]);
  const top = stack[stack.length - 1];

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
  const key = 'id' in top ? `${top.name}:${top.id}` : top.name;
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
