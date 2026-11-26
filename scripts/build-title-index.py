"""Build the offline TV search seed from a complete AGE v2/catalog JSON response."""
import json
import re
import sys
from pathlib import Path

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
target = Path(__file__).resolve().parents[1] / "app/src/main/res/raw/age_title_index.json"
target.write_text(json.dumps([entries[k] for k in sorted(entries)], ensure_ascii=False, separators=(",", ":")) + "\n")
