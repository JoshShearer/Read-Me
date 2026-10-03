import { StyleSheet } from 'react-native';
import { read, type } from './theme';

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
// REA-28 layout: a 24 dp page margin, everything left aligned. Article words (titles,
// paragraphs) use read.* from theme.ts; controls and metadata use the sans scale.
const MARGIN = 24;

export const ui = StyleSheet.create({
  screen: { flex: 1 },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingLeft: MARGIN,
    paddingRight: MARGIN - 12,
    paddingTop: 16,
    paddingBottom: 12,
    gap: 4,
  },
  headerTitle: { flex: 1, ...read.display },
  // The Reader's header names the item, which can be long: smaller, two lines at most.
  itemTitle: { flex: 1, ...read.title, fontWeight: '600' },
  // Buttons are ./Button (REA-28): they read as buttons by shape and the primary colour.
  row: { paddingHorizontal: MARGIN, paddingVertical: 12 },
  // A list item: its title is the article's, so it is serif.
  entry: { paddingHorizontal: MARGIN, paddingTop: 20, paddingBottom: 12, gap: 2 },
  entryTitle: { ...read.title },
  // Settings' section headings: the Material 3 preference-category style, sentence case.
  section: { ...type.sm, fontWeight: '600', paddingHorizontal: MARGIN, paddingTop: 28, paddingBottom: 8 },
  title: type.base,
  small: type.sm,
  meta: { ...type.xs, fontSize: 13, lineHeight: 18 },
  actions: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 4, marginLeft: -12 },
  // A control on its own line keeps its own width rather than stretching across the row.
  start: { alignSelf: 'flex-start' },
  // A control under a section title, on the row's left edge.
  inset: { paddingHorizontal: MARGIN, paddingVertical: 4 },
  stack: { gap: 8 },
  // A label and its input on one line.
  field: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  play: { minWidth: 104 },
  empty: { paddingHorizontal: MARGIN, paddingVertical: 32 },
  card: { marginHorizontal: MARGIN - 8, marginVertical: 16, padding: 20, borderWidth: 1, borderRadius: 16, gap: 12 },
  paragraph: { ...read.body, paddingHorizontal: MARGIN, paddingVertical: 8 },
  cut: { textDecorationLine: 'line-through' },
  link: { flexDirection: 'row', alignItems: 'center', marginTop: 16 },
  grow: { flex: 1 },
  chevron: { ...type['2xl'] },
  end: { height: 32 },
  divider: { height: StyleSheet.hairlineWidth, marginHorizontal: MARGIN },
  // Reading progress on a list item: a thin track with the read part in primary.
  track: { height: 4, borderRadius: 2, marginTop: 10, overflow: 'hidden' },
  fill: { height: 4, borderRadius: 2 },
  // The Reader's controls float above the text in a dock. surfaceContainerLow, because
  // disabled glyphs (faint) keep 3:1 on it (3.05 light, 3.92 dark) and not on
  // surfaceContainerHigh (2.78 light).
  dock: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginHorizontal: 12,
    marginBottom: 8,
    paddingHorizontal: 4,
    paddingVertical: 6,
    borderRadius: 32,
    borderWidth: StyleSheet.hairlineWidth,
  },
});
