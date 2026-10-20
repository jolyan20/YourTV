#!/usr/bin/env python3
"""Morning and evening adhkar for the screen, each one checked against the six books -> assets/shared/adhkar.json.

assets/shared/adhkar-src.json lists the candidates. A dhikr is kept only if its exact words (letters only, harakat
and punctuation ignored, as in verify_hadith.py) appear inside a hadith of the six books (fawazahmed0/hadith-api),
and not only in narrations Al-Albani grades weak. A Quran entry takes its text from api.alquran.cloud (the same
edition as the verses of the day) and is kept only if its "proof" (the hadith that names it for morning and
evening) is found the same way. Each kept entry records the books it was found in.

  python .github/tools/build_adhkar.py
"""
import json
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from verify_hadith import BOOKS, GRADED, URL, norm, weak_grades   # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "assets/shared/adhkar-src.json"
OUT = ROOT / "assets/shared/adhkar.json"
SHORT = {"ara-bukhari": "البخاري", "ara-muslim": "مسلم", "ara-abudawud": "أبو داود", "ara-tirmidhi": "الترمذي",
         "ara-nasai": "النسائي", "ara-ibnmajah": "ابن ماجه"}


def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "YourTV adhkar check"}), timeout=120) as r:
        return json.loads(r.read())


def books():
    out = {}
    for ed in BOOKS:
        hs = get(URL.format(ed))["hadiths"]
        grades = {}
        if ed in GRADED:
            for h in get(URL.format(GRADED[ed]))["hadiths"]:
                grades[h["hadithnumber"]] = h.get("grades") or []
        out[ed] = [(h["hadithnumber"], norm(h.get("text", "")), grades.get(h["hadithnumber"], [])) for h in hs]
        print(ed, len(out[ed]), file=sys.stderr)
    return out


def found_in(words, lib):
    key = norm(words)
    hits = {}
    for ed, hs in lib.items():
        for num, text, grades in hs:
            if key in text and not weak_grades(grades):
                hits.setdefault(SHORT[ed], []).append(num)
    return hits


BASMALA = "بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ "


def quran(refs):
    """The verses' text; the API puts the basmala in front of each surah's first verse: shown without it."""
    out = []
    for r in refs:
        t = get(f"https://api.alquran.cloud/v1/ayah/{r}/quran-simple")["data"]["text"]
        if r.endswith(":1") and not r.startswith("1:") and t.startswith(BASMALA):
            t = t[len(BASMALA):]
        out.append(t)
    return " ".join(out)


def main():
    lib = books()
    out = []
    for c in json.loads(SRC.read_text(encoding="utf-8")):
        # the words themselves, and (when given) the hadith that sets them for morning or evening, with the count
        hits = found_in(c.get("proof") or c["text"], lib)
        if hits and c.get("proof") and not c.get("quran") and not found_in(c["text"], lib):
            hits = {}
        label = c.get("title") or c["text"][:40]
        if not hits:
            print("NOT FOUND, left out:", label, file=sys.stderr)
            continue
        e = {"when": c["when"], "count": c["count"], "found": hits}
        if c.get("title"):
            e["title"] = c["title"]
        e["text"] = quran(c["quran"]) if c.get("quran") else c["text"]
        if c.get("quran"):
            e["quran"] = True
        out.append(e)
        print("ok:", label, hits, file=sys.stderr)
    OUT.write_text(json.dumps(out, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"{len(out)} of {len(json.loads(SRC.read_text(encoding='utf-8')))} kept", file=sys.stderr)


if __name__ == "__main__":
    main()
