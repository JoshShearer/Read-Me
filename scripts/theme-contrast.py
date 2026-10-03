#!/usr/bin/env python3
"""Measures text and status-bar contrast in screenshots from `npm run device:themes`.

Usage: theme-contrast.py <dir>   (reads <name>.png and <name>.xml pairs from the dir)

Every TextView in the UI dump is cropped from its screenshot. Its background is the crop's
most common colour and its foreground the 90th percentile, by luminance distance, of the
pixels that differ from it (so anti-aliased edges do not decide, and a stray pixel neither). The status bar strip is measured
the same way. Contrast is the WCAG 2 ratio. The report carries no text from the screen: only
the screen name, the node's index and its bounds, so it can be pasted anywhere.

SCREENSHOT_MASK="x0,y0,x1,y1" (raw pixels, as for device:screenshots) names another app's
floating overlay. Text nodes that overlap it are skipped and counted, never measured.

Thresholds: 4.5 for text, 3.0 for text drawn deliberately faint (a cut paragraph, a disabled
control) and for the status bar icons. Exit 1 if anything is below its threshold.
"""
import os
import re
import sys
from collections import Counter
from pathlib import Path

from PIL import Image

NODE = re.compile(r'<node [^>]*>')
ATTR = re.compile(r'(\S+)="([^"]*)"')
STATUS_BAR_PX = 120  # the reference device's status bar is 0-140; stay inside it


def lum(rgb):
    def ch(c):
        c = c / 255
        return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = rgb[:3]
    return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b)


def ratio(a, b):
    la, lb = sorted((lum(a), lum(b)), reverse=True)
    return (la + 0.05) / (lb + 0.05)


def contrast(img, box):
    crop = img.crop(box).convert('RGB')
    px = list(crop.getdata())
    if not px:
        return None, None, None
    bg = Counter(px).most_common(1)[0][0]
    lbg = lum(bg)
    # Glyph pixels: everything not the background. A short word fills a few percent of a wide
    # crop, so the percentile is taken among these, not among all pixels.
    ink = sorted((p for p in px if abs(lum(p) - lbg) > 0.002), key=lambda p: abs(lum(p) - lbg))
    if len(ink) < 20:
        return 1.0, bg, bg  # nothing drawn that differs from the background
    fg = ink[int(len(ink) * 0.9)]
    return ratio(bg, fg), bg, fg


HIGHLIGHT = (0xff, 0xe6, 0x80)


def highlight(img):
    """The Reader's sentence highlight: its colour against the text drawn on it, or None."""
    rgb = img.convert('RGB')
    w, h = rgb.size
    hits = [(x, y) for y in range(STATUS_BAR_PX, h, 4) for x in range(0, w, 4)
            if all(abs(a - b) <= 6 for a, b in zip(rgb.getpixel((x, y)), HIGHLIGHT))]
    if len(hits) < 50:
        return None
    xs, ys = [p[0] for p in hits], [p[1] for p in hits]
    box = (min(xs), min(ys), max(xs) + 1, max(ys) + 1)
    # Text on the highlight: the darkest pixels inside the highlighted rows' span.
    px = sorted(rgb.crop(box).getdata(), key=lum)
    fg = px[int(len(px) * 0.02)]
    return ratio(HIGHLIGHT, fg)


def faint(attrs, ancestors):
    """A node drawn faint on purpose: a cut Trim paragraph or a disabled control."""
    desc = ' '.join(a.get('content-desc', '') for a in ancestors + [attrs])
    return ' cut' in desc or any(a.get('enabled') == 'false' for a in ancestors + [attrs])


def nodes(xml):
    """TextViews with their ancestors' attributes (for cut and disabled state)."""
    out, stack = [], []
    for tok in re.finditer(r'<node [^>]*?/>|<node [^>]*>|</node>', xml):
        t = tok.group(0)
        if t == '</node>':
            stack.pop()
            continue
        attrs = dict(ATTR.findall(t))
        if attrs.get('class') == 'android.widget.TextView' and attrs.get('text'):
            out.append((attrs, list(stack)))
        if not t.endswith('/>'):
            stack.append(attrs)
    return out


def overlaps(a, b):
    return a[0] < b[2] and b[0] < a[2] and a[1] < b[3] and b[1] < a[3]


def main(d):
    d = Path(d)
    bad = 0
    m = os.environ.get('SCREENSHOT_MASK')
    mask = tuple(int(v) for v in m.split(',')) if m else None
    masked = 0
    for png in sorted(d.glob('*.png')):
        xml = png.with_suffix('.xml')
        img = Image.open(png)
        sb, bg, fg = contrast(img, (0, 0, img.width, STATUS_BAR_PX))
        ok = sb >= 3.0
        bad += not ok
        print(f'{png.stem:18} status-bar        {sb:5.2f} bg={bg} fg={fg} {"ok" if ok else "LOW"}')
        hl = highlight(img)
        if hl is not None:
            ok = hl >= 4.5
            bad += not ok
            print(f'{png.stem:18} highlight         {hl:5.2f} {"ok" if ok else "LOW"}')
        if not xml.exists():
            continue
        worst = None
        for i, (a, anc) in enumerate(nodes(xml.read_text(errors='replace'))):
            x0, y0, x1, y1 = map(int, re.findall(r'\d+', a['bounds']))
            if y1 - y0 < 8 or x1 - x0 < 8 or y0 < STATUS_BAR_PX:
                continue
            if mask and overlaps((x0, y0, x1, y1), mask):
                masked += 1
                continue
            r, nbg, nfg = contrast(img, (x0, y0, x1, y1))
            need = 3.0 if faint(a, anc) else 4.5
            if r < need:
                bad += 1
                print(f'{png.stem:18} text#{i:<3} {a["bounds"]:24} {r:5.2f} need {need} bg={nbg} fg={nfg} LOW')
            if worst is None or r < worst[0]:
                worst = (r, i, need)
        if worst:
            print(f'{png.stem:18} lowest text#{worst[1]:<3}      {worst[0]:5.2f} (need {worst[2]})')
    if mask:
        print(f'skipped {masked} text node(s) under SCREENSHOT_MASK')
    print('contrast PASS' if bad == 0 else f'contrast FAIL ({bad} below threshold)')
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1]))
