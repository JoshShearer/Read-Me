// R-M02: a share with exactly one distinct http(s) URL is a link item; anything else is a
// text item stored as shared. Never logs and never builds a message from the shared text.
export type ShareClass =
  | { kind: 'link'; url: string }
  | { kind: 'text'; text: string }
  | { kind: 'empty' };

// A URL also stops at typographic quotes and full-width punctuation: phone keyboards type
// smart quotes by default, and CJK prose puts 。 or ， straight after a URL with no space.
const URL_PATTERN =
  /\bhttps?:\/\/[^\s<>"'`\u201c\u201d\u2018\u2019\u00ab\u00bb\u2039\u203a\u300c\u300d\u300e\u300f\u3001\u3002\uff0c\uff1b\uff1a\uff01\uff1f\u2026]+/gi;
const TRAILING = '.,;:!?\'"';
const PAIRS: Record<string, string> = { ')': '(', ']': '[', '}': '{' };

function count(s: string, ch: string): number {
  let n = 0;
  for (const c of s) if (c === ch) n++;
  return n;
}

/** Strips punctuation that wraps or ends a URL in prose, keeping balanced brackets. */
function trimUrl(raw: string): string {
  let url = raw;
  for (;;) {
    const last = url[url.length - 1];
    if (TRAILING.includes(last)) {
      url = url.slice(0, -1);
      continue;
    }
    const open = PAIRS[last];
    if (open !== undefined && count(url, open) < count(url, last)) {
      url = url.slice(0, -1);
      continue;
    }
    return url;
  }
}

export function classifyShare(shared: string): ShareClass {
  if (shared.trim() === '') return { kind: 'empty' };
  const urls = new Set((shared.match(URL_PATTERN) ?? []).map(trimUrl));
  if (urls.size === 1) return { kind: 'link', url: [...urls][0] };
  return { kind: 'text', text: shared };
}
