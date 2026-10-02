import { StyleSheet } from 'react-native';

// One small style sheet for every screen: R-M01 asks for a light interface, not a theme.
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
