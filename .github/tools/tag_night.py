#!/usr/bin/env python3
"""Mark the photos that suit the night (sunsets, night skies, dark scenes) with "n": 1 in photos/index.json.
From Maghrib to sunrise the screen picks only from those; by day only from the others.

  pip install pillow
  python .github/tools/tag_night.py
"""
import json
import re
from pathlib import Path

from PIL import Image, ImageStat

ROOT = Path(__file__).resolve().parents[2]
INDEX = ROOT / "photos" / "index.json"
NIGHT_WORDS = re.compile(r"غروب|ليل|ليلية|ليلاً|قمر|القمر|نجوم|النجوم|مساء|الغسق|سماء الليل")

index = json.loads(INDEX.read_text(encoding="utf-8"))
for p in index:
    lum = ImageStat.Stat(Image.open(ROOT / "photos" / p["f"]).convert("L").resize((192, 108))).mean[0]
    night = bool(NIGHT_WORDS.search(p.get("t", ""))) or lum < 55
    if night:
        p["n"] = 1
    else:
        p.pop("n", None)
INDEX.write_text(json.dumps(index, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
print(f"{sum(1 for p in index if p.get('n'))} night photos of {len(index)}")
