"""Build from a complete AGE v2/catalog response and bangumi-data dist/data.json (in that order)."""
import json
import re
import sys
from collections import defaultdict
from datetime import date
from pathlib import Path
import unicodedata

def normalized(title):
    return "".join(unicodedata.normalize("NFKC", title).casefold().split())

def release_date(value):
    try:
        return date.fromisoformat((value or "")[:10])
    except ValueError:
        return None

def names(item):
    return [title for title in [item["title"], item.get("originalTitle"), *item.get("otherTitles", [])] if title]


catalog = json.loads(Path(sys.argv[1]).read_text())
entries = {}
for video in catalog["videos"]:
    anime_id = int(video["id"])
    title = (video["name"] or "").strip()
    if anime_id <= 0 or not title:
        raise ValueError(f"Invalid AGE entry {anime_id}")
    item = {"animeId": anime_id, "title": title}
    original = (video.get("name_original") or "").strip()
    if original and original != title:
        item["originalTitle"] = original
    aliases = [a.strip() for a in re.split(r"[/／|｜]", video.get("name_other") or "")]
    aliases = list(dict.fromkeys(a for a in aliases if a and a not in (title, original)))
    if aliases:
        item["otherTitles"] = aliases
    entries[anime_id] = item
if len(entries) != int(catalog["total"]) or not entries:
    raise ValueError("Expected a complete catalog with distinct titles by AGE id")
source = json.loads(Path(sys.argv[2]).read_text())["items"]
age_by_name = defaultdict(set)
dates = {int(v["id"]): release_date(v.get("premiere")) for v in catalog["videos"]}
for anime_id, item in entries.items():
    for title in names(item):
        age_by_name[normalized(title)].add(anime_id)

# Keep separate seasons and remakes distinct. Multiple possible matches in either
# direction, incomplete dates, and aliases shared with another work are skipped.
matches = defaultdict(list)
owners = defaultdict(set)
for row, show in enumerate(source):
    translations = show.get("titleTranslate", {})
    labels = [show["title"]] + [t for lang in ("zh-Hans", "zh-Hant", "en") for t in translations.get(lang, [])]
    labels = list(dict.fromkeys(t.strip() for t in labels if t.strip()))
    premiered = release_date(show.get("begin"))
    candidates = set()
    for label in labels:
        key = normalized(label)
        owners[key].add(row)
        if premiered:
            candidates.update(i for i in age_by_name[key] if dates[i] and abs((dates[i] - premiered).days) <= 2)
    if len(candidates) == 1:
        matches[candidates.pop()].append(labels)

added = enriched = 0
for anime_id, possible in matches.items():
    if len(possible) != 1:
        continue
    item = entries[anime_id]
    known = {normalized(t) for t in names(item)}
    extra = []
    for label in possible[0]:
        key = normalized(label)
        if key not in known and len(owners[key]) == 1 and not (age_by_name[key] - {anime_id}):
            extra.append(label)
            known.add(key)
    if extra:
        item.setdefault("otherTitles", []).extend(extra)
        added += len(extra)
        enriched += 1
print(f"{len(entries)} AGE titles, {len(source)} external records, {added} new aliases across {enriched} titles")
target = Path(__file__).resolve().parents[1] / "app/src/main/res/raw/age_title_index.json"
target.write_text(json.dumps([entries[k] for k in sorted(entries)], ensure_ascii=False, separators=(",", ":")) + "\n")
