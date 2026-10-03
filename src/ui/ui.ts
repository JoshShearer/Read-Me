import { StyleSheet } from 'react-native';
import { type } from './theme';

// One small style sheet for every screen. Layout and type only: colours come from theme.ts per
// system mode (REA-22, REA-24), applied by ./Text's tone and by each screen's backgrounds and
// borders. Nothing here sets opacity: faint and secondary text get their colour from the tone,
// and opacity on top would cut the contrast the tone was picked for.
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
  headerTitle: { flex: 1, ...type.xl, fontWeight: '600' },
  // Buttons are ./Button (REA-28): they read as buttons by shape and the primary colour.
  row: { paddingHorizontal: 16, paddingVertical: 12 },
  title: type.base,
  small: type.sm,
  actions: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 8 },
  // A control on its own line keeps its own width rather than stretching across the row.
  start: { alignSelf: 'flex-start' },
  // A control under a section title, on the row's left edge.
  inset: { paddingHorizontal: 16, paddingVertical: 4 },
  stack: { gap: 8 },
  play: { minWidth: 96 },
  empty: { padding: 24 },
  card: { margin: 16, padding: 16, borderWidth: 1, borderRadius: 12, gap: 8 },
  paragraph: { ...type.lg, paddingHorizontal: 16, paddingVertical: 6 },
  cut: { textDecorationLine: 'line-through' },
  transport: {
    flexDirection: 'row',
    justifyContent: 'space-around',
    alignItems: 'center',
    paddingVertical: 10,
    borderTopWidth: StyleSheet.hairlineWidth,
  },
});
