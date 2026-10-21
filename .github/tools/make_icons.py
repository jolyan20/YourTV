"""Draw the app's icon and TV banner (and the phone page's icon) -> android res/ and assets/shared/.

The idea: the screen of a Saudi home. A gold crescent holds a clock face (the time, the prayers), an eight-point
star at its centre (the Islamic geometry of the card titles), on the deep green of the screen at night.
The banner adds the name «تلفزيونك» in Thmanyah Display. Run: python .github/tools/make_icons.py
(needs pillow, fonttools, brotli, uharfbuzz).
"""
import io
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "android/app/src/main/res"
GOLD, GOLD_LT, CREAM, GREEN, GREEN_DK = (220, 184, 111), (246, 214, 140), (250, 243, 228), (12, 52, 36), (4, 20, 13)


def star(d, cx, cy, r, fill):
    pts = []
    for k in range(16):   # two squares turned 45°: the eight-point star (khatam)
        a = math.pi / 8 * k - math.pi / 2
        rr = r if k % 2 == 0 else r * 0.62
        pts.append((cx + rr * math.cos(a), cy + rr * math.sin(a)))
    d.polygon(pts, fill=fill)


def emblem(size, rounded=True, pad=0.0):
    """the square icon, drawn 4× and scaled down for clean edges"""
    S = size * 4
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    # background: deep green, lighter towards the top left
    bg = Image.new("RGBA", (S, S))
    g = ImageDraw.Draw(bg)
    for y in range(S):
        t = y / S
        g.line([(0, y), (S, y)], fill=tuple(int(GREEN[i] * (1 - t) + GREEN_DK[i] * t) for i in range(3)) + (255,))
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * (0.22 if rounded else 0)), fill=255)
    img.paste(bg, (0, 0), mask)
    d = ImageDraw.Draw(img)
    c = S / 2
    R = S * (0.36 - pad)
    # the crescent: a gold disc with a disc cut out, opening to the upper right, around the clock
    cres = Image.new("L", (S, S), 0)
    cd = ImageDraw.Draw(cres)
    cd.ellipse([c - R, c - R, c + R, c + R], fill=255)
    off = R * 0.30
    cd.ellipse([c - R * 0.86 + off, c - R * 0.86 - off * 0.55, c + R * 0.86 + off, c + R * 0.86 - off * 0.55], fill=0)
    glow = Image.new("RGBA", (S, S), GOLD + (0,))
    glow.putalpha(cres.filter(ImageFilter.GaussianBlur(S * 0.02)).point(lambda v: v * 0.5))
    img.alpha_composite(glow)
    gold = Image.new("RGBA", (S, S), GOLD_LT + (255,))
    img.paste(gold, (0, 0), cres)
    # the clock face inside the crescent's hollow
    fr = R * 0.56
    fx, fy = c + off * 0.55, c - off * 0.32
    d.ellipse([fx - fr, fy - fr, fx + fr, fy + fr], fill=CREAM + (255,))
    d.ellipse([fx - fr, fy - fr, fx + fr, fy + fr], outline=GOLD + (255,), width=int(S * 0.012))
    for k in range(12):   # the hours: longer marks at 12, 3, 6, 9
        a = math.pi / 6 * k
        r1, r2 = fr * (0.80 if k % 3 == 0 else 0.86), fr * 0.93
        d.line([(fx + r1 * math.sin(a), fy - r1 * math.cos(a)), (fx + r2 * math.sin(a), fy - r2 * math.cos(a))],
               fill=GREEN + (255,), width=int(S * (0.014 if k % 3 == 0 else 0.008)))
    # hands at ten past ten, then the star over their pivot
    for ang, ln, w in ((-60, 0.48, 0.022), (60, 0.70, 0.016)):
        a = math.radians(ang)
        d.line([(fx, fy), (fx + fr * ln * math.sin(a), fy - fr * ln * math.cos(a))], fill=GREEN + (255,), width=int(S * w))
    star(d, fx, fy, fr * 0.17, GOLD + (255,))
    d.ellipse([fx - fr * 0.05, fy - fr * 0.05, fx + fr * 0.05, fy + fr * 0.05], fill=GREEN + (255,))
    return img.resize((size, size), Image.LANCZOS)


_FONT = {}


def _ttf():
    """ThmanyahDisplay as plain TrueType bytes (the repo has it as woff2)"""
    if "b" not in _FONT:
        from fontTools.ttLib import TTFont
        f = TTFont(ROOT / "assets/fonts/ThmanyahDisplay-Bold.woff2")
        f.flavor = None
        b = io.BytesIO()
        f.save(b)
        _FONT["b"] = b.getvalue()
        _FONT["tt"] = TTFont(io.BytesIO(_FONT["b"]))
    return _FONT["b"], _FONT["tt"]


class _Poly:
    """a fontTools pen that flattens a glyph's outline into polygons"""
    def __init__(self):
        self.polys, self.cur = [], []

    def moveTo(self, p): self.cur = [p]
    def lineTo(self, p): self.cur.append(p)

    def qCurveTo(self, *pts):
        from fontTools.pens.basePen import decomposeQuadraticSegment
        for c, e in decomposeQuadraticSegment(pts):
            s0 = self.cur[-1]
            for i in range(1, 9):
                t = i / 8
                self.cur.append(((1-t)**2*s0[0] + 2*(1-t)*t*c[0] + t*t*e[0], (1-t)**2*s0[1] + 2*(1-t)*t*c[1] + t*t*e[1]))

    def curveTo(self, *pts):
        s0 = self.cur[-1]
        c1, c2, e = pts[-3:]
        for i in range(1, 11):
            t = i / 10
            self.cur.append(tuple((1-t)**3*s0[k] + 3*(1-t)**2*t*c1[k] + 3*(1-t)*t*t*c2[k] + t**3*e[k] for k in (0, 1)))

    def closePath(self):
        if len(self.cur) > 2: self.polys.append(self.cur)
        self.cur = []

    endPath = closePath
    def addComponent(self, *a): pass


def text(img, s, px, right, top, fill):
    """Arabic text shaped by HarfBuzz (joined letters, right to left), ending at x=right"""
    import uharfbuzz as hb
    from PIL import ImageChops
    data, tt = _ttf()
    face = hb.Face(hb.Blob(data))
    f = hb.Font(face)
    buf = hb.Buffer()
    buf.add_str(s)
    buf.guess_segment_properties()
    hb.shape(f, buf, {})
    k = px / face.upem
    width = sum(p.x_advance for p in buf.glyph_positions) * k
    order, gs = tt.getGlyphOrder(), tt.getGlyphSet()
    asc = tt["hhea"].ascent * k
    mask = Image.new("L", img.size, 0)
    x = right - width
    for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
        pen = _Poly()
        gs[order[info.codepoint]].draw(pen)
        ox, oy = x + pos.x_offset * k, top + asc - pos.y_offset * k
        for poly in pen.polys:   # even-odd: the holes of ه and و stay open
            m = Image.new("L", img.size, 0)
            ImageDraw.Draw(m).polygon([(ox + a * k, oy - b * k) for a, b in poly], fill=255)
            mask = ImageChops.difference(mask, m)
        x += pos.x_advance * k
    img.paste(Image.new("RGBA", img.size, fill + (255,)), (0, 0), mask)
    return width


def banner(w, h):
    S = 4
    W, H = w * S, h * S
    img = Image.new("RGBA", (W, H))
    g = ImageDraw.Draw(img)
    for x in range(W):   # green, darker to the left where the text sits
        t = x / W
        g.line([(x, 0), (x, H)], fill=tuple(int(GREEN_DK[i] * (1 - t) + GREEN[i] * t) for i in range(3)) + (255,))
    ic = emblem(int(H * 0.66 / S), rounded=True).resize((int(H * 0.66), int(H * 0.66)), Image.LANCZOS)
    img.alpha_composite(ic, (W - ic.width - int(H * 0.12), int(H * 0.17)))
    right = W - ic.width - int(H * 0.22)
    text(img, "تلفزيونك", int(H * 0.21), right, int(H * 0.22), GOLD_LT)
    text(img, "شاشة البيت", int(H * 0.105), right, int(H * 0.58), (216, 205, 182))
    return img.resize((w, h), Image.LANCZOS)


if __name__ == "__main__":
    for folder, px in (("mipmap-xhdpi", 96), ("mipmap-xxhdpi", 144), ("mipmap-xxxhdpi", 192)):
        emblem(px).save(RES / folder / "ic_launcher.png")
    banner(320, 180).save(RES / "drawable-xhdpi/banner.png")
    banner(480, 270).save(RES / "drawable-xxhdpi/banner.png")
    emblem(512, rounded=False).convert("RGB").save(ROOT / "assets/shared/control-icon.png")   # iOS rounds it itself
    print("icons written")
