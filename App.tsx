// R-M01: four screens, opening on the list. A route stack plus the hardware back button;
// four routes do not need a navigation library (AGENTS.md 14 keeps dependencies minimal).
import React, { useCallback, useEffect, useState } from 'react';
import { BackHandler, StatusBar, Text, View } from 'react-native';
import { SafeAreaProvider, useSafeAreaInsets } from 'react-native-safe-area-context';
import { markOpened, type Item } from './src/library/library';
import { ListScreen } from './src/ui/ListScreen';
import { TrimScreen } from './src/ui/TrimScreen';
import { back, openRoute, push, trimDone, type Route } from './src/ui/model';
import { ui } from './src/ui/ui';

function Main() {
  const insets = useSafeAreaInsets();
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
    default:
      screen = <Text style={ui.empty}>Coming in this phase</Text>;
  }
  return <View style={[ui.screen, { paddingTop: insets.top }]}>{screen}</View>;
}

export default function App() {
  return (
    <SafeAreaProvider>
      <StatusBar barStyle="default" />
      <Main />
    </SafeAreaProvider>
  );
}
