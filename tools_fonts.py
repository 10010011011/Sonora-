"""Converts dm-sans-latin.woff2 / manrope-latin.woff2 (from the web app) into static TTF weights for res/font.
Needs: pip install fonttools brotli.  Usage: python3 tools_fonts.py <out_dir>"""
import os, sys
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
JOBS = [
    ("dm-sans-latin.woff2", "dm_sans", {400: "regular", 500: "medium", 600: "semibold", 700: "bold"}),
    ("manrope-latin.woff2", "manrope", {400: "regular", 600: "semibold", 700: "bold", 800: "extrabold"}),
]
for src, prefix, weights in JOBS:
    if not os.path.exists(src):
        print("WARN missing", src)
        continue
    for wght, suffix in weights.items():
        font = TTFont(src)
        font.flavor = None
        if "fvar" in font:
            axes = {a.axisTag: a for a in font["fvar"].axes}
            loc = {tag: a.defaultValue for tag, a in axes.items()}
            if "wght" in axes:
                loc["wght"] = max(axes["wght"].minValue, min(axes["wght"].maxValue, float(wght)))
            font = instancer.instantiateVariableFont(font, loc)
        font.save(os.path.join(out, "%s_%s.ttf" % (prefix, suffix)))
        print("wrote", prefix, suffix)
