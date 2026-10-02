import { classifyShare } from '../src/intake/classify';
import { splitSharedText } from '../src/intake/paragraphs';

describe('classifyShare (R-M02)', () => {
  test('a bare URL is a link item', () => {
    expect(classifyShare('https://example.com/a')).toEqual({
      kind: 'link',
      url: 'https://example.com/a',
    });
  });

  test('"Title https://..." is a link item, query kept as shared', () => {
    expect(
      classifyShare('Great read https://example.com/a?utm_source=x&id=2'),
    ).toEqual({ kind: 'link', url: 'https://example.com/a?utm_source=x&id=2' });
  });

  test('wrapping punctuation is not part of the URL', () => {
    expect(classifyShare('Read this: https://x.com/a.')).toEqual({
      kind: 'link',
      url: 'https://x.com/a',
    });
    expect(classifyShare('(see https://x.com/a)')).toEqual({
      kind: 'link',
      url: 'https://x.com/a',
    });
    expect(classifyShare('"https://x.com/a"')).toEqual({
      kind: 'link',
      url: 'https://x.com/a',
    });
  });

  test('a balanced closing parenthesis stays (Wikipedia style)', () => {
    expect(classifyShare('https://en.wikipedia.org/wiki/Foo_(bar)')).toEqual({
      kind: 'link',
      url: 'https://en.wikipedia.org/wiki/Foo_(bar)',
    });
  });

  test('the scheme is matched case-insensitively', () => {
    expect(classifyShare('HTTPS://EXAMPLE.COM/A')).toEqual({
      kind: 'link',
      url: 'HTTPS://EXAMPLE.COM/A',
    });
  });

  test('two different URLs make a text item (no guessing)', () => {
    const shared = 'compare https://a.com/x and https://b.com/y';
    expect(classifyShare(shared)).toEqual({ kind: 'text', text: shared });
  });

  test('the same URL twice is still one link', () => {
    expect(classifyShare('https://a.com/x https://a.com/x')).toEqual({
      kind: 'link',
      url: 'https://a.com/x',
    });
  });

  test('non-http schemes and scheme-less hosts are text', () => {
    expect(classifyShare('ftp://a.com/x')).toEqual({
      kind: 'text',
      text: 'ftp://a.com/x',
    });
    expect(classifyShare('see example.com/a')).toEqual({
      kind: 'text',
      text: 'see example.com/a',
    });
  });

  test('text with no URL is stored as shared', () => {
    const shared = 'Line one\n\nLine two';
    expect(classifyShare(shared)).toEqual({ kind: 'text', text: shared });
  });

  test('an empty or blank share is empty', () => {
    expect(classifyShare('')).toEqual({ kind: 'empty' });
    expect(classifyShare(' \n\t ')).toEqual({ kind: 'empty' });
  });
});

describe('splitSharedText (R-M04, text items)', () => {
  const texts = (s: string) => splitSharedText(s).map(p => p.text);

  test('blank lines separate paragraphs; single newlines join', () => {
    expect(texts('A\nb.\n\nC.')).toEqual(['A b.', 'C.']);
  });

  test('CRLF, blank lines holding spaces, and paragraph separators', () => {
    expect(texts('A\r\n\r\nB\n \t\nC\u2029D')).toEqual(['A', 'B', 'C', 'D']);
  });

  test('leading and trailing blank lines and runs of spaces collapse', () => {
    expect(texts('\n\n  one   two \n\n\n')).toEqual(['one two']);
  });

  test('every paragraph is kind p, and empty text gives none', () => {
    expect(splitSharedText('x\n\ny').every(p => p.kind === 'p')).toBe(true);
    expect(splitSharedText('   ')).toEqual([]);
  });
});
