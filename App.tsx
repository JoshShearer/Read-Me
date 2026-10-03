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
  // Each screen gets its own unflattened view, keyed by route. Fabric flattens layout-only
  // views, so screens' children were mounted straight into a shared parent, and swapping List
  // for Trim intermittently crashed with "addViewAt: failed to insert view ... at index N"
  // (parent [2] = SafeAreaProvider, then [34] = this wrapper once it was unflattened;
  // reproduced 2026-10-02 on builds 65ef1b2 and e9574f4, about 4 in 10 taps). With a keyed
  // view per route a switch removes one native view and inserts a new one.
  const key = 'id' in top ? `${top.name}:${top.id}` : top.name;
  // REA-22, REA-24: the status bar sits on this surface, so its icons follow the mode too.
  return (
    <View collapsable={false} style={[ui.screen, { paddingTop: insets.top, backgroundColor: palette(scheme).surface }]}>
      <StatusBar barStyle={statusBarStyle(scheme)} />
      <View key={key} collapsable={false} style={ui.screen}>
        {screen}
      </View>
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
