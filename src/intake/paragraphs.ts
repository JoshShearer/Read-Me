import type { Paragraph } from '../types';

// R-M04: shared text is split into paragraphs on blank lines; single newlines are joined.
export function splitSharedText(text: string): Paragraph[] {
  return text
    .replace(/\r\n?/g, '\n')
    .replace(/\u2029/g, '\n\n')
    .split(/\n[^\S\n]*\n/)
    .map(p => p.replace(/\s+/g, ' ').trim())
    .filter(p => p.length > 0)
    .map(p => ({ kind: 'p', text: p }));
}
