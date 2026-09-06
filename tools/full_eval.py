"""
Runs a candidate prompt against all 181 (well, 180 - one known-bad ground
truth label excluded) records and produces the full language x field
breakdown: English-artist-wrong / Hebrew-artist-wrong / English-album-wrong
/ Hebrew-album-wrong, using the lenient (format-tolerant) matcher.

Usage: python full_eval.py prompts/vN.py [output_tag]
"""
import json
import os
import re
import sys
import unicodedata
from concurrent.futures import ThreadPoolExecutor, as_completed
from difflib import SequenceMatcher

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.stdout.reconfigure(encoding="utf-8", errors="replace")

from eval_prompt import recognize_one, load_prompt, SCRATCH

HEBREW_RE = re.compile(r'[֐-׿]')


def is_hebrew(s):
    return bool(HEBREW_RE.search(s or ''))


def normalize(s):
    s = unicodedata.normalize("NFKC", s or "")
    s = "".join(c for c in s if not unicodedata.combining(c))
    s = s.casefold().strip()
    s = re.sub(r"[‘’']", "'", s)
    s = re.sub(r"[.:,]", " ", s)
    s = re.sub(r"\s*[/|]\s*.*$", "", s)
    s = re.sub(r"\([^)]*\)", " ", s)
    s = re.sub(r"\s*=\s*.*$", "", s)
    s = re.sub(r"\band\b", "&", s)
    s = re.sub(r"[^\w\s&']", " ", s, flags=re.UNICODE)
    s = re.sub(r"\s+", " ", s).strip()
    s = re.sub(r"^(the)\s+", "", s)
    return s


def is_match(want, got, threshold=0.72):
    a, b = normalize(want), normalize(got)
    if not a and not b:
        return True
    if a == b:
        return True
    if SequenceMatcher(None, a, b).ratio() >= threshold:
        return True
    wa, wb = set(a.split()), set(b.split())
    if wa and wb and (wa <= wb or wb <= wa):
        return True
    return False


def main():
    prompt_path = sys.argv[1]
    tag = sys.argv[2] if len(sys.argv) > 2 else os.path.splitext(os.path.basename(prompt_path))[0]
    prompt_text = load_prompt(prompt_path)

    dev_set = json.load(open(os.path.join(SCRATCH, "full_181_ground_truth.json"), encoding="utf-8"))
    print(f"Evaluating {tag} on {len(dev_set)} records (full corpus)...")

    results = {}
    with ThreadPoolExecutor(max_workers=8) as ex:
        futures = {ex.submit(recognize_one, item["filename"], prompt_text): item for item in dev_set}
        done = 0
        for fut in as_completed(futures):
            item = futures[fut]
            results[item["filename"]] = fut.result()
            done += 1
            if done % 20 == 0:
                print(f"  ...{done}/{len(dev_set)}")

    rows = []
    for item in dev_set:
        r = results[item["filename"]]
        a_ok = is_match(item["gt_artist"], r["artistName"])
        b_ok = is_match(item["gt_album"], r["albumName"])
        rows.append({
            "filename": item["filename"],
            "gt_artist": item["gt_artist"], "got_artist": r["artistName"], "a_match": a_ok,
            "gt_album": item["gt_album"], "got_album": r["albumName"], "b_match": b_ok,
            "both_match": a_ok and b_ok,
            "is_hebrew": is_hebrew(item["gt_artist"]) or is_hebrew(item["gt_album"]),
            "confidence": r["confidence"], "error": r["error"],
        })

    out_path = os.path.join(SCRATCH, f"full_eval_{tag}.json")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(rows, f, ensure_ascii=False, indent=2)

    total = len(rows)
    both_ok = sum(1 for r in rows if r["both_match"])
    hebrew_rows = [r for r in rows if r["is_hebrew"]]
    english_rows = [r for r in rows if not r["is_hebrew"]]

    heb_artist_wrong = sum(1 for r in hebrew_rows if not r["a_match"])
    heb_album_wrong = sum(1 for r in hebrew_rows if not r["b_match"])
    eng_artist_wrong = sum(1 for r in english_rows if not r["a_match"])
    eng_album_wrong = sum(1 for r in english_rows if not r["b_match"])

    print(f"\n=== {tag}: full corpus ({total} records) ===")
    print(f"Overall correct (both fields): {both_ok}/{total}")
    print(f"Hebrew records: {len(hebrew_rows)} total")
    print(f"  Hebrew artist wrong: {heb_artist_wrong}")
    print(f"  Hebrew album wrong:  {heb_album_wrong}")
    print(f"English records: {len(english_rows)} total")
    print(f"  English artist wrong: {eng_artist_wrong}")
    print(f"  English album wrong:  {eng_album_wrong}")
    print(f"\nWritten to {out_path}")


if __name__ == "__main__":
    main()
