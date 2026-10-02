import { StyleSheet } from 'react-native';

// One small style sheet for every screen: R-M01 asks for a light interface, not a theme.
//
// Every View in the screens is collapsable={false}. Fabric flattens layout-only Views and
// unflattens them when their props change; under a screen switch that churn drops a view's
// Create mutation (react/react-native#58265, closed unfixed; #58175 is the related merge
// bug), which crashed List to Trim with "addViewAt: failed to insert view" or left it blank
// on about half of taps (device:ui and a repro loop, 2026-10-02). With no flattenable wrapper
// the same loop opened 10 of 10 under its worst timing.
export const ui = StyleSheet.create({
  screen: { flex: 1 },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 12,
    gap: 12,
  },
  headerTitle: { flex: 1, fontSize: 20, fontWeight: '600' },
  button: { paddingHorizontal: 10, paddingVertical: 8 },
  buttonText: { fontSize: 15, textDecorationLine: 'underline' },
  disabled: { opacity: 0.3 },
  row: { paddingHorizontal: 16, paddingVertical: 12 },
  title: { fontSize: 16 },
  small: { fontSize: 13, opacity: 0.7 },
  actions: { flexDirection: 'row', gap: 4, marginTop: 4 },
  empty: { padding: 24 },
  card: { margin: 16, padding: 16, borderWidth: 1, borderRadius: 8, gap: 8 },
  paragraph: { fontSize: 17, lineHeight: 26, paddingHorizontal: 16, paddingVertical: 6 },
  cut: { opacity: 0.35, textDecorationLine: 'line-through' },
  highlight: { backgroundColor: '#ffe680', color: '#000' },
  transport: {
    flexDirection: 'row',
    justifyContent: 'space-around',
    alignItems: 'center',
    paddingVertical: 10,
    borderTopWidth: StyleSheet.hairlineWidth,
  },
});
