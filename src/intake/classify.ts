// R-M02: a share with exactly one distinct http(s) URL is a link item; anything else is a
// text item stored as shared. Never logs and never builds a message from the shared text.
export type ShareClass =
  | { kind: 'link'; url: string }
  | { kind: 'text'; text: string }
  | { kind: 'empty' };

const URL_PATTERN = /\bhttps?:\/\/[^\s<>"'`]+/gi;
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
