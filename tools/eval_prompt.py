"""
Evaluation harness for prompt-tuning GeminiRecognitionService's prompt.
Runs a candidate prompt against a dev set and scores artist/album accuracy
against ground truth (examples/recognition_results.json).

Usage: python tools/eval_prompt.py tools/prompts/current.py
  The prompt file must define a top-level PROMPT string.
  Reads tools/eval_data/dev_set.json — a list of
  {filename, group, gt_artist, gt_album} — regenerate it from
  examples/recognition_results.json (ground truth) vs. a saved raw AI
  snapshot for whatever record set you want to test against; see
  RESULTS.md for how the original 2026-09 dev set was built.
  Scores with exact-normalized matching; use rescore_eval.py afterward
  for a punctuation/format-tolerant rescore (Gemini's own output varies
  run to run even for an identical prompt on a hard cover).
"""
import base64
import importlib.util
import io
import json
import os
import re
import sys
import unicodedata
from concurrent.futures import ThreadPoolExecutor, as_completed
from difflib import SequenceMatcher

import requests
from PIL import Image

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EXAMPLES_DIR = os.path.join(REPO, "examples")
SCRATCH = os.path.join(REPO, "tools", "eval_data")
os.makedirs(SCRATCH, exist_ok=True)

with open(os.path.join(REPO, "local.properties"), "r", encoding="utf-8") as f:
    API_KEY = next(l.split("=", 1)[1].strip() for l in f if l.strip().startswith("gemini_api_key"))

GEMINI_URL = f"https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key={API_KEY}"
MAX_DIM = 1024
session = requests.Session()


def compress_bytes(raw, max_dim, quality):
    img = Image.open(io.BytesIO(raw)).convert("RGB")
    w, h = img.size
    scale = min(max_dim / w, max_dim / h, 1.0)
    if scale < 1.0:
        img = img.resize((max(1, int(w * scale)), max(1, int(h * scale))), Image.LANCZOS)
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=quality)
    return buf.getvalue()


def recognize_one(filename, prompt_text):
    path = os.path.join(EXAMPLES_DIR, filename)
    photo_bytes = compress_bytes(open(path, "rb").read(), MAX_DIM, 85)
    payload = {
        "contents": [{"parts": [
            {"text": prompt_text},
            {"inlineData": {"mimeType": "image/jpeg", "data": base64.b64encode(photo_bytes).decode("ascii")}},
        ]}],
        "generationConfig": {"thinkingConfig": {"thinkingBudget": 8000}},
    }
    for attempt in range(3):
        try:
            resp = session.post(GEMINI_URL, json=payload, timeout=60)
            if resp.status_code != 200:
                continue
            data = resp.json()
            parts = data["candidates"][0]["content"]["parts"]
            text = ""
            for p in reversed(parts):
                if not p.get("thought", False) and "text" in p:
                    text = p["text"]
                    break
            cleaned = text.strip()
            cleaned = re.sub(r"^```json", "", cleaned).strip()
            cleaned = re.sub(r"^```", "", cleaned).strip()
            cleaned = re.sub(r"```$", "", cleaned).strip()
            result = json.loads(cleaned)
            return {
                "filename": filename,
                "artistName": result.get("artistName", ""),
                "albumName": result.get("albumName", ""),
                "year": result.get("year", ""),
                "numRecords": result.get("numRecords", ""),
                "confidence": result.get("confidence", "low"),
                "error": None,
            }
        except Exception as e:
            last_err = f"{type(e).__name__}: {e}"
    return {"filename": filename, "artistName": "", "albumName": "", "year": "", "numRecords": "",
            "confidence": "low", "error": last_err}


def normalize(s):
    s = unicodedata.normalize("NFKC", s or "")
    s = "".join(c for c in s if not unicodedata.combining(c))  # strip Hebrew nikud
    s = s.strip().casefold()
    s = re.sub(r"[\u2018\u2019']", "'", s)
    s = re.sub(r"\s+", " ", s)
    s = re.sub(r"^(the)\s+", "", s)
    return s


def fuzzy_ratio(a, b):
    return SequenceMatcher(None, normalize(a), normalize(b)).ratio()


def load_prompt(path):
    spec = importlib.util.spec_from_file_location("prompt_module", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.PROMPT


def main():
    prompt_path = sys.argv[1]
    full = "--full" in sys.argv
    prompt_text = load_prompt(prompt_path)

    dev_set = json.load(open(os.path.join(SCRATCH, "dev_set.json"), encoding="utf-8"))
    if full:
        table = json.load(open(os.path.join(SCRATCH, "table_data.json"), encoding="utf-8"))
        by_file = {r["filename"]: r for r in table}
        dev_set = [{"filename": r["filename"], "group": "full",
                    "gt_artist": r["updated"]["artistName"], "gt_album": r["updated"]["albumName"]}
                   for r in table if r["filename"] != "20260906_094938.jpg"]

    print(f"Evaluating {os.path.basename(prompt_path)} on {len(dev_set)} records...")

    results = {}
    with ThreadPoolExecutor(max_workers=8) as ex:
        futures = {ex.submit(recognize_one, item["filename"], prompt_text): item for item in dev_set}
        done = 0
        for fut in as_completed(futures):
            item = futures[fut]
            r = fut.result()
            results[item["filename"]] = r
            done += 1
            if done % 20 == 0:
                print(f"  ...{done}/{len(dev_set)}")

    rows = []
    for item in dev_set:
        r = results[item["filename"]]
        a_match = normalize(r["artistName"]) == normalize(item["gt_artist"])
        b_match = normalize(r["albumName"]) == normalize(item["gt_album"])
        rows.append({
            "filename": item["filename"], "group": item["group"],
            "gt_artist": item["gt_artist"], "got_artist": r["artistName"], "a_match": a_match,
            "gt_album": item["gt_album"], "got_album": r["albumName"], "b_match": b_match,
            "both_match": a_match and b_match,
            "confidence": r["confidence"], "error": r["error"],
        })

    total = len(rows)
    both_ok = sum(1 for r in rows if r["both_match"])
    mistakes_rows = [r for r in rows if r["group"] == "mistake"]
    regression_rows = [r for r in rows if r["group"] == "regression"]
    mistakes_fixed = sum(1 for r in mistakes_rows if r["both_match"])
    regressions_broken = sum(1 for r in regression_rows if not r["both_match"])

    print(f"\n=== {os.path.basename(prompt_path)} ===")
    print(f"Overall: {both_ok}/{total} match ground truth")
    if mistakes_rows:
        print(f"Previously-wrong fixed: {mistakes_fixed}/{len(mistakes_rows)}")
    if regression_rows:
        print(f"Previously-right now BROKEN: {regressions_broken}/{len(regression_rows)}")

    out_path = os.path.join(SCRATCH, f"eval_{os.path.splitext(os.path.basename(prompt_path))[0]}.json")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(rows, f, ensure_ascii=False, indent=2)
    print(f"Details written to {out_path}")

    if regressions_broken:
        print("\n--- REGRESSIONS (were right, now wrong) ---")
        for r in regression_rows:
            if not r["both_match"]:
                print(f"  {r['filename']}: '{r['gt_artist']} - {r['gt_album']}' -> got '{r['got_artist']} - {r['got_album']}'")

    still_wrong = [r for r in mistakes_rows if not r["both_match"]]
    if still_wrong:
        print(f"\n--- STILL WRONG ({len(still_wrong)}) ---")
        for r in still_wrong:
            print(f"  {r['filename']}: want '{r['gt_artist']} - {r['gt_album']}' -> got '{r['got_artist']} - {r['got_album']}'")


if __name__ == "__main__":
    main()
