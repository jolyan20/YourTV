#!/usr/bin/env python3
"""Add photos from the Saudi Photos Hub (cc.spa.gov.sa, Saudi Press Agency, CC BY-SA 4.0) to photos/.

Takes the «مناطق ومعالم» (regions and landmarks) section, keeps landscapes and landmarks (no meetings, offices or
events), spreads the picks over the regions, crops each to 1920x1080 and adds it to photos/index.json with its
Arabic title as the caption. Credit: «واس» (docs/PHOTO-CREDITS.md lists every photo).

  pip install pillow
  python .github/tools/fetch_spa.py [how many, default 80]
"""
import io
import json
import re
import sys
import time
import urllib.request
from collections import defaultdict
from pathlib import Path

from PIL import Image, ImageStat

ROOT = Path(__file__).resolve().parents[2]
PHOTOS = ROOT / "photos"
SITE, CDN = "https://cc.spa.gov.sa", "https://cc-cdn.spa.gov.sa/mashaa"
SECTION = "4642/1058"   # مناطق ومعالم
UA = {"User-Agent": "YourTV screensaver photo picker (github.com/jolyan20/YourTV)"}

SKIP = re.compile(r"مقر|هيئة|وزارة|مستشفى|جامعة|مطار|محطة|مؤتمر|معرض|اجتماع|زيارة|حفل|فعالية|مبنى|إدارة|"
                  r"شركة|مصنع|مشروع|استقبال|توقيع|ملتقى|منتدى|افتتاح|مركز|مدرسة|طلاب|أمير|وزير|سمو")
REGIONS = ["الرياض", "جدة", "مكة", "المدينة", "الشرقية", "الدمام", "الخبر", "الأحساء", "القطيف", "الجبيل",
           "عسير", "أبها", "الباحة", "جازان", "نجران", "الطائف", "تبوك", "العلا", "حائل", "الجوف",
           "الحدود الشمالية", "القصيم", "ينبع", "أملج", "الوجه", "فرسان", "الدرعية", "رجال ألمع", "السودة"]


def get(url, tries=3):
    for i in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
                return r.read()
        except Exception:
            if i == tries - 1:
                raise
            time.sleep(3 * (i + 1))


def listing():
    items, page = [], 0
    while True:
        batch = json.loads(get(f"{SITE}/Utility/GetMoreNewList/{SECTION}/{page}"))
        if not batch:
            return items
        items += batch
        total = int(batch[0].get("total") or 0)
        page += 1
        if len(items) >= total or page > 60:
            return items


def caption(title):
    t = re.sub(r"\s*\(\d{4}\)\s*$", "", title).strip()
    t = re.sub(r"\s+(?:في|ب)?(?:جنوب|شمال|شرق|غرب)(?:\s+|ي\s+)?(?:غربي|شرقي)?\s*(?:ب|في\s+)?السعودية$", "", t)
    t = re.sub(r"\s+(?:في|ب)السعودية$|\s+بالسعودية$|\s+في السعودية$", "", t)
    return t.strip()


def region(title):
    return next((r for r in REGIONS if r in title), "أخرى")


def prepare(raw):
    im = Image.open(io.BytesIO(raw)).convert("RGB")
    w, h = im.size
    if w / h < 1.3 or w < 1600:
        return None, None                      # portrait or small: not for a TV
    tw = round(h * 16 / 9)
    if tw <= w:
        left = (w - tw) // 2
        im = im.crop((left, 0, left + tw, h))
    else:
        th = round(w * 9 / 16)
        top = (h - th) // 2
        im = im.crop((0, top, w, top + th))
    im = im.resize((1920, 1080), Image.LANCZOS)
    # a black frame or a mostly black side (night shots, scans) looks broken on a TV: skip it
    small = im.convert("L").resize((192, 108))
    w2, h2 = small.size
    def dark(box):
        px = list(small.crop(box).getdata())
        return sum(1 for v in px if v < 12) / len(px)
    if max(dark((0, 0, w2 // 7, h2)), dark((w2 - w2 // 7, 0, w2, h2)),
           dark((0, 0, w2, h2 // 7)), dark((0, h2 - h2 // 7, w2, h2))) > 0.9:
        return None, None
    lum = ImageStat.Stat(im.convert("L")).mean[0]
    d = max(0.7, min(1.0, 1.0 - max(0.0, lum - 90) / 250))   # brighter photos are darkened a little more
    out = io.BytesIO()
    im.save(out, "JPEG", quality=82, optimize=True, progressive=True)
    return out.getvalue(), round(d, 2)


def main():
    want = int(sys.argv[1]) if len(sys.argv) > 1 else 80
    index = json.loads((PHOTOS / "index.json").read_text(encoding="utf-8"))
    have = {p["f"] for p in index}
    pool = [x for x in listing() if x.get("mediaId") and not SKIP.search(x.get("title", ""))]
    seen_titles, by_region = set(), defaultdict(list)
    for x in pool:
        c = caption(x["title"])
        if c in seen_titles:
            continue
        seen_titles.add(c)
        by_region[region(x["title"])].append(x)
    # round-robin over the regions so no single place fills the set
    order, k = [], 0
    while any(by_region.values()):
        for r in list(by_region):
            if by_region[r]:
                order.append(by_region[r].pop(0))
        k += 1
    added = []
    for x in order:
        if len(added) >= want:
            break
        name = f"spa-{x['mediaId']}.jpg"
        if name in have:
            continue
        try:
            meta = json.loads(get(f"{SITE}/Utility/GetOneImage/{x['mediaId']}/0"))
            jpg, d = prepare(get(CDN + meta["umbracoFile"]))
        except Exception as e:
            print("skip", x["mediaId"], e)
            continue
        if not jpg:
            continue
        (PHOTOS / name).write_bytes(jpg)
        entry = {"f": name, "c": "واس", "t": caption(x["title"]), "d": d,
                 "src": f"{SITE}/ar/news/{x['id']}"}
        index.append(entry)
        added.append(entry)
        print(len(added), name, entry["t"])
        time.sleep(0.5)
    (PHOTOS / "index.json").write_text(json.dumps(index, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"added {len(added)}; index has {len(index)}")


if __name__ == "__main__":
    main()
