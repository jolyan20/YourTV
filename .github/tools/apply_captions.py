#!/usr/bin/env python3
"""Give every photo in photos/index.json its Arabic caption ("t", shown small on the screen) and list the
Saudi Photos Hub ones in docs/PHOTO-CREDITS.md.

  python .github/tools/apply_captions.py
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
INDEX = ROOT / "photos" / "index.json"
CREDITS = ROOT / "docs" / "PHOTO-CREDITS.md"
HEAD = "## Saudi Photos Hub (`photos/spa-*.jpg`)"

index = json.loads(INDEX.read_text(encoding="utf-8"))
captions = json.loads((Path(__file__).with_name("captions-ar.json")).read_text(encoding="utf-8"))
for p in index:
    if not p.get("t") and p["f"] in captions:
        p["t"] = captions[p["f"]]
INDEX.write_text(json.dumps(index, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")

spa = [p for p in index if p["f"].startswith("spa-")]
text = CREDITS.read_text(encoding="utf-8").split("\n" + HEAD)[0].rstrip() + "\n"
text += (f"\n{HEAD}\n\nFrom the [Saudi Photos Hub](https://cc.spa.gov.sa) of the Saudi Press Agency (واس), under\n"
         "[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). Cropped to 1920x1080; credit: واس.\n\n"
         "| File | Caption | Source |\n|---|---|---|\n")
text += "".join(f"| `photos/{p['f']}` | {p.get('t', '')} | [واس]({p.get('src', 'https://cc.spa.gov.sa')}) |\n" for p in spa)
CREDITS.write_text(text, encoding="utf-8")
print(f"{len(index)} photos, {sum(1 for p in index if p.get('t'))} with captions, {len(spa)} from واس")
