#!/usr/bin/env python3
"""Checks an open action sheet's rows in a uiautomator dump (REA-62).

Usage: sheet-check.py <dump.xml> <density dpi> <screen width px> <screen height px> <row resource-id>...

Each row (found by its resource-id, a React Native testID) must be present, have area, lie on
the screen and be at least 48 dp tall (within 2 px of rounding). No two rows overlap by more
than 2 px: rows of a fractional pixel height are rounded edge by edge, so neighbours can share
a pixel row (60 dp at 597 dpi is 223.9 px; seen on the phone, 2026-10-22). Each row's
label (its text node) must lie inside the row and be strictly shorter than it: uiautomator
clips a child's bounds to its parent, so a label that wrapped past a fixed-height row shows up
as a label as tall as the row, or as one reaching past it.

Prints one line per finding, with ids and bounds only (no label text), then `sheet: PASS` or
`sheet: FAIL`; exits 1 on FAIL and 2 on an empty or unreadable dump ("no capture").
"""
import re
import sys
import xml.etree.ElementTree as ET

BOUNDS = re.compile(r'\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]')


def px(dp, density):
    return round(dp * density / 160)


def bounds(node):
    m = BOUNDS.fullmatch(node.get('bounds', ''))
    return tuple(int(v) for v in m.groups()) if m else None


def check(xml, density, screen_w, screen_h, ids):
    """Returns (findings, ok). findings carry ids and bounds only."""
    root = ET.fromstring(xml)
    by_id = {}
    for n in root.iter('node'):
        by_id.setdefault(n.get('resource-id', ''), n)
    out = []
    min_h = px(48, density) - 2
    rows = {}
    for rid in ids:
        n = by_id.get(rid)
        if n is None:
            out.append(f'FAIL sheet: {rid} missing')
            continue
        b = bounds(n)
        if b is None or not (b[0] < b[2] and b[1] < b[3]):
            out.append(f'FAIL sheet: {rid} has no area {n.get("bounds")}')
            continue
        x0, y0, x1, y1 = b
        if x0 < 0 or y0 < 0 or x1 > screen_w or y1 > screen_h:
            out.append(f'FAIL sheet: {rid} off screen {n.get("bounds")}')
        if y1 - y0 < min_h:
            out.append(f'FAIL sheet: {rid} {y1 - y0} px tall, below 48 dp ({min_h + 2} px) {n.get("bounds")}')
        labels = [c for c in n.iter('node') if c is not n and c.get('text') and bounds(c)]
        if not labels:
            out.append(f'FAIL sheet: {rid} has no label node')
        for c in labels:
            cb = bounds(c)
            if not (cb[0] >= x0 and cb[1] >= y0 and cb[2] <= x1 and cb[3] <= y1):
                out.append(f'FAIL sheet: {rid} label {c.get("bounds")} outside its row {n.get("bounds")}')
            elif cb[3] - cb[1] >= y1 - y0:
                out.append(f'FAIL sheet: {rid} label clipped to its row ({cb[3] - cb[1]} px of {y1 - y0})')
        rows[rid] = b
        out.append(f'sheet: {rid} [{x0},{y0}][{x1},{y1}]')
    names = list(rows)
    for i, a in enumerate(names):
        for b in names[i + 1:]:
            p, q = rows[a], rows[b]
            if p[0] < q[2] and q[0] < p[2] and min(p[3], q[3]) - max(p[1], q[1]) > 2:
                out.append(f'FAIL sheet: {a} overlaps {b}')
    ok = not any(line.startswith('FAIL') for line in out)
    return out, ok


def main(argv):
    if len(argv) < 6:
        print('usage: sheet-check.py <dump.xml> <density> <screen w> <screen h> <row id>...')
        return 2
    path, density, screen_w, screen_h, ids = argv[1], int(argv[2]), int(argv[3]), int(argv[4]), argv[5:]
    try:
        with open(path, encoding='utf-8') as f:
            xml = f.read()
        if '<node' not in xml:
            raise ValueError('empty')
        findings, ok = check(xml, density, screen_w, screen_h, ids)
    except (OSError, ValueError, ET.ParseError):
        print(f'sheet: no capture ({path})')
        return 2
    for line in findings:
        print(line)
    print('sheet: PASS' if ok else 'sheet: FAIL')
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main(sys.argv))
