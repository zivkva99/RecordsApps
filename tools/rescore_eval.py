"""Lenient re-scorer: takes an eval_*.json (raw got/want pairs) and rescopes
with punctuation/format-tolerant fuzzy matching, so cosmetic formatting
variance (Emerson Lake & Palmer vs Emerson, Lake & Palmer) and Gemini's
run-to-run sampling noise don't get counted as real mistakes.
"""
import json
import re
import sys
import unicodedata
from difflib import SequenceMatcher

sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def normalize(s):
    s = unicodedata.normalize("NFKC", s or "")
    s = "".join(c for c in s if not unicodedata.combining(c))  # strip Hebrew nikud
    s = s.casefold().strip()
    s = re.sub(r"[‘’']", "'", s)
    s = re.sub(r"[.:,]", " ", s)
    s = re.sub(r"\s*[/|]\s*.*$", "", s)          # drop " / alt title" or " | alt title"
    s = re.sub(r"\([^)]*\)", " ", s)              # drop parenthetical subtitle/gloss
    s = re.sub(r"\s*=\s*.*$", "", s)              # drop " = English gloss"
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
    ratio = SequenceMatcher(None, a, b).ratio()
    if ratio >= threshold:
        return True
    # token-subset check: one title fully contains the other's core words
    wa, wb = set(a.split()), set(b.split())
    if wa and wb and (wa <= wb or wb <= wa):
        return True
    return False


def main():
    path = sys.argv[1]
    rows = json.load(open(path, encoding="utf-8"))
    for r in rows:
        r["a_match"] = is_match(r["gt_artist"], r["got_artist"])
        r["b_match"] = is_match(r["gt_album"], r["got_album"])
        r["both_match"] = r["a_match"] and r["b_match"]

    mistakes = [r for r in rows if r["group"] == "mistake"]
    regression = [r for r in rows if r["group"] == "regression"]
    total_ok = sum(1 for r in rows if r["both_match"])

    print(f"=== Lenient rescore: {path} ===")
    print(f"Overall: {total_ok}/{len(rows)}")
    if mistakes:
        fixed = sum(1 for r in mistakes if r["both_match"])
        print(f"Previously-wrong fixed: {fixed}/{len(mistakes)}")
    if regression:
        broken = sum(1 for r in regression if not r["both_match"])
        print(f"Previously-right now broken: {broken}/{len(regression)}")
        if broken:
            print("  Broken:")
            for r in regression:
                if not r["both_match"]:
                    print(f"    {r['filename']}: want '{r['gt_artist']} - {r['gt_album']}' -> got '{r['got_artist']} - {r['got_album']}'")

    still_wrong = [r for r in mistakes if not r["both_match"]]
    print(f"\nStill wrong ({len(still_wrong)}):")
    for r in still_wrong:
        print(f"  {r['filename']}: want '{r['gt_artist']} - {r['gt_album']}' -> got '{r['got_artist']} - {r['got_album']}'")

    out_path = path.replace(".json", "_lenient.json")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(rows, f, ensure_ascii=False, indent=2)


if __name__ == "__main__":
    main()
