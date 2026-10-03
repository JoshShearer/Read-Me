#!/usr/bin/env python3
"""Scales device screenshots to 1080 px wide for the store listing.

    tidy-screenshots.py <raw-dir> <out-dir>

SCREENSHOT_MASK="x0,y0,x1,y1" (raw pixels) covers another app's floating overlay, such as a
keyboard-switcher bubble, with the colour beside it. It refuses if the strip beside the region
is not one flat colour, because then the patch would hide or invent app content.
"""
import os
import sys

from PIL import Image

raw, out = sys.argv[1], sys.argv[2]
mask = os.environ.get('SCREENSHOT_MASK')
for name in sorted(os.listdir(raw)):
    if not name.endswith('.png'):
        continue
    im = Image.open(os.path.join(raw, name)).convert('RGB')
    if mask:
        x0, y0, x1, y1 = (int(v) for v in mask.split(','))
        strip = im.crop((x1, y0, x1 + 40, y1))
        colours = strip.getcolors(maxcolors=4)
        if not colours or len(colours) != 1:
            sys.exit('%s: the strip beside the mask is not flat; not patching' % name)
        im.paste(colours[0][1], (x0, y0, x1, y1))
    w = 1080
    im = im.resize((w, round(im.height * w / im.width)), Image.LANCZOS)
    im.save(os.path.join(out, name), optimize=True)
    print(os.path.join(out, name))
