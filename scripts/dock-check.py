#!/usr/bin/env python3
"""Checks the Reader's dock in a uiautomator dump (REA-58).

Usage: dock-check.py <dump.xml> <density dpi> <screen height px> <play|pause>

uiautomator reports bounds clipped to the parent views and the screen, so a control pushed
past the right edge is not reported with x1 > width: it is missing, has no area (x0 >= x1),
or is narrower than it is drawn. So each control must be present, have area, be on screen
above the screen's bottom, and meet its size: every glyph button exactly 48 dp square (within
2 px of rounding), Play/Pause at least 48 dp wide and 40 dp tall (its 4 dp vertical hitSlop
makes the 48 dp target, which a dump cannot show). A text node inside a control must be
strictly smaller than the control (a glyph in height, Play's label in width), or the text was
clipped to the control's box.
No two controls overlap. The rate value must be on screen.

Prints one line per finding, with labels and bounds only (no item text), then
`dock: PASS` or `dock: FAIL`; exits 1 on FAIL and 2 on an empty or unreadable dump ("no
capture"), which is not a finding about the dock.
"""
import re
import sys
import xml.etree.ElementTree as ET

BOUNDS = re.compile(r'\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]')
ICONS = ['back paragraph', 'previous sentence', 'next sentence', 'slower', 'faster']
RATE = re.compile(r'^\d+\.\dx$')


def px(dp, density):
    return round(dp * density / 160)


def bounds(node):
    m = BOUNDS.fullmatch(node.get('bounds', ''))
    return tuple(int(v) for v in m.groups()) if m else None


def check(xml, density, screen_h, transport):
    """Returns (findings, ok). findings are strings carrying labels and bounds only."""
    root = ET.fromstring(xml)
    nodes = list(root.iter('node'))
    by_desc = {}
    for n in nodes:
        by_desc.setdefault(n.get('content-desc', ''), n)
    out = []
    side = px(48, density)
    controls = {}
    for name in ICONS + [transport]:
        n = by_desc.get(name)
        if n is None:
            out.append(f'FAIL dock: {name} missing')
            continue
        b = bounds(n)
        if b is None or not (b[0] < b[2] and b[1] < b[3]):
            out.append(f'FAIL dock: {name} has no area {n.get("bounds")}')
            continue
        x0, y0, x1, y1 = b
        w, h = x1 - x0, y1 - y0
        if x0 < 0 or y1 > screen_h:
            out.append(f'FAIL dock: {name} off screen {n.get("bounds")}')
        if name == transport:
            if w < side or h < px(40, density):
                out.append(f'FAIL dock: {name} {w}x{h} px, below {side}x{px(40, density)} {n.get("bounds")}')
        elif abs(w - side) > 2 or abs(h - side) > 2:
            out.append(f'FAIL dock: {name} {w}x{h} px, not {side}x{side} (clipped) {n.get("bounds")}')
        for c in n.iter('node'):
            cb = bounds(c)
            if c is n or cb is None or not c.get('text'):
                continue
            # The pill grows to its label's height, so for Play/Pause only the width tells.
            if name == transport and cb[2] - cb[0] >= w:
                out.append(f'FAIL dock: {name} label clipped to its box ({cb[2] - cb[0]} px of {w})')
            elif name != transport and cb[3] - cb[1] >= h:
                out.append(f'FAIL dock: {name} glyph clipped to its box ({cb[3] - cb[1]} px of {h})')
        controls[name] = b
    names = list(controls)
    for i, a in enumerate(names):
        for b in names[i + 1:]:
            p, q = controls[a], controls[b]
            if p[0] < q[2] and q[0] < p[2] and p[1] < q[3] and q[1] < p[3]:
                out.append(f'FAIL dock: {a} overlaps {b}')
    rate = [n for n in nodes if RATE.match(n.get('text', ''))]
    rb = bounds(rate[0]) if rate else None
    if rb is None or not (rb[0] < rb[2] and rb[1] < rb[3]):
        out.append('FAIL dock: rate value missing or has no area')
    if transport in controls:
        x0, y0, x1, y1 = controls[transport]
        out.append(f'dock: {transport} centre {(x0 + x1) // 2},{(y0 + y1) // 2}')
    for name, b in controls.items():
        out.append(f'dock: {name} [{b[0]},{b[1]}][{b[2]},{b[3]}]')
    ok = not any(line.startswith('FAIL') for line in out)
    return out, ok


def main(argv):
    path, density, screen_h, transport = argv[1], int(argv[2]), int(argv[3]), argv[4]
    try:
        with open(path, encoding='utf-8') as f:
            xml = f.read()
        if '<node' not in xml:
            raise ValueError('empty')
        findings, ok = check(xml, density, screen_h, transport)
    except (OSError, ValueError, ET.ParseError):
        print(f'dock: no capture ({path})')
        return 2
    for line in findings:
        print(line)
    print('dock: PASS' if ok else 'dock: FAIL')
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main(sys.argv))
