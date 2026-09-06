"""
Runs the app's own Gemini recognition prompt (mirrored from
GeminiRecognitionService.kt) against every photo in examples/, for bulk
QA / prompt-tuning outside the app.

Usage: python tools/recognize_covers.py
Requires: pip install requests Pillow
Reads the Gemini API key from local.properties (gemini_api_key=...).
Writes tools/output/recognition_results.json and tools/output/thumbs/.
"""

import base64
import io
import json
import os
import re
import sys
import time
import traceback
from concurrent.futures import ThreadPoolExecutor, as_completed
from threading import Lock

import requests
from PIL import Image

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
_save_lock = Lock()

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EXAMPLES_DIR = os.path.join(REPO, "examples")
OUTPUT_DIR = os.path.join(REPO, "tools", "output")
OUT_JSON = os.path.join(OUTPUT_DIR, "recognition_results.json")
THUMB_DIR = os.path.join(OUTPUT_DIR, "thumbs")
os.makedirs(THUMB_DIR, exist_ok=True)

# read api key from local.properties
API_KEY = None
with open(os.path.join(REPO, "local.properties"), "r", encoding="utf-8") as f:
    for line in f:
        if line.strip().startswith("gemini_api_key"):
            API_KEY = line.split("=", 1)[1].strip()
if not API_KEY:
    print("No API key found")
    sys.exit(1)

MAX_DIM = 1024  # matches app's compressImage()

# Exact prompt copied from GeminiRecognitionService.kt PROMPT constant
PROMPT = """You are a music expert and vinyl record collector with encyclopedic knowledge of album covers, artwork, and discography. Your knowledge includes Discogs, AllMusic, Wikipedia, Spotify, and music databases worldwide.

Your task: identify the vinyl record in this photo.

READING TEXT ON THE COVER
Read all visible text. Filter out non-title elements: label logos, catalog numbers, "stereo/mono", copyright notices, price stickers, and corner budget series labels (e.g. CBS Israel "25/25", French "Impact", "Hallmark") are never the album title.
On many covers the band/artist name and the album title appear on separate lines — read them as distinct fields; never confuse one for the other.
Read all text completely and exactly — do not truncate, alter, or replace any word.
When reading names in any script, transcribe each word exactly. In Hebrew: ס (samech) and מ (mem) are completely different letters — "סשה" is "Sasha" (S), NOT "Moshe" (which starts with מ). Do not substitute a more famous artist who shares only the surname.
If only the band/artist name is visible with no separate album title, this may be a self-titled album — consider that possibility, especially for bands active in the late 1960s whose debut album shares the band name.

IDENTIFYING THE ALBUM
If prominent text gives you a hypothesis, verify by recalling the actual cover art of that specific album and comparing it to what you see. If the artwork does not match, discard the hypothesis. Then scan the artist COMPLETE discography — including debut albums and early 1960s/1970s works — and match the artwork against each known cover.
If no useful text is visible, identify purely from the visual artwork. As a last resort, use your broadest knowledge as if doing a reverse image search.
If multiple song titles are listed on the front cover, this is a compilation — use "low" confidence.

YEAR — CRITICAL
Use ONLY the original first commercial release year from Discogs or AllMusic. After identifying the album, ask yourself: "When was this specific album (not a live version, not a compilation, not a reissue) FIRST released?" Report that year precisely. Self-titled debut albums from the late 1960s may be from 1968-1970 even if the band is better known for later work.

Return ONLY a JSON object:
{
  "artistName": "...",
  "albumName": "...",
  "year": "...",
  "numRecords": "...",
  "confidence": "high" or "low"
}
Rules: "year" = 4-digit original first release; "numRecords" = disc count; "confidence" = "high" only if certain of ALL fields; empty string if unknown."""

URL = f"https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key={API_KEY}"


def compress_image(path):
    """Mirror app's compressImage(): scale down to MAX_DIM, JPEG quality 85."""
    img = Image.open(path)
    img = img.convert("RGB")
    w, h = img.size
    scale = min(MAX_DIM / w, MAX_DIM / h, 1.0)
    if scale < 1.0:
        img = img.resize((max(1, int(w * scale)), max(1, int(h * scale))), Image.LANCZOS)
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=85)
    return buf.getvalue()


def make_thumb(path, filename):
    """Small thumbnail for the HTML report."""
    img = Image.open(path)
    img = img.convert("RGB")
    img.thumbnail((260, 260), Image.LANCZOS)
    out_path = os.path.join(THUMB_DIR, os.path.splitext(filename)[0] + ".jpg")
    img.save(out_path, format="JPEG", quality=60)
    return out_path


def parse_gemini_response(body):
    data = json.loads(body)
    parts = data["candidates"][0]["content"]["parts"]
    text = ""
    for part in reversed(parts):
        if not part.get("thought", False) and "text" in part:
            text = part["text"]
            break
    cleaned = text.strip()
    cleaned = re.sub(r"^```json", "", cleaned).strip()
    cleaned = re.sub(r"^```", "", cleaned).strip()
    cleaned = re.sub(r"```$", "", cleaned).strip()
    result = json.loads(cleaned)
    return {
        "artistName": result.get("artistName", ""),
        "albumName": result.get("albumName", ""),
        "year": result.get("year", ""),
        "numRecords": result.get("numRecords", ""),
        "confidence": result.get("confidence", "low"),
    }


def recognize_one(filename):
    path = os.path.join(EXAMPLES_DIR, filename)
    try:
        make_thumb(path, filename)
        image_bytes = compress_image(path)
        b64 = base64.b64encode(image_bytes).decode("ascii")

        payload = {
            "contents": [
                {
                    "parts": [
                        {"text": PROMPT},
                        {"inlineData": {"mimeType": "image/jpeg", "data": b64}},
                    ]
                }
            ],
            "generationConfig": {"thinkingConfig": {"thinkingBudget": 8000}},
        }

        last_err = None
        for attempt in range(3):
            try:
                resp = requests.post(URL, json=payload, timeout=60)
                if resp.status_code != 200:
                    last_err = f"HTTP {resp.status_code}: {resp.text[:300]}"
                    time.sleep(2 * (attempt + 1))
                    continue
                parsed = parse_gemini_response(resp.text)
                return {"filename": filename, "error": None, **parsed}
            except Exception as e:
                last_err = str(e)
                time.sleep(2 * (attempt + 1))
        return {
            "filename": filename,
            "error": last_err,
            "artistName": "",
            "albumName": "",
            "year": "",
            "numRecords": "",
            "confidence": "low",
        }
    except Exception as e:
        return {
            "filename": filename,
            "error": f"{type(e).__name__}: {e}",
            "artistName": "",
            "albumName": "",
            "year": "",
            "numRecords": "",
            "confidence": "low",
        }


def main():
    files = sorted(
        f for f in os.listdir(EXAMPLES_DIR) if f.lower().endswith((".jpg", ".jpeg"))
    )
    print(f"Found {len(files)} images")

    results = []
    done = 0
    with ThreadPoolExecutor(max_workers=8) as ex:
        futures = {ex.submit(recognize_one, f): f for f in files}
        for fut in as_completed(futures):
            r = fut.result()
            results.append(r)
            done += 1
            status = "ERR" if r["error"] else r["confidence"].upper()
            print(f"[{done}/{len(files)}] {r['filename']:40s} {status:5s} {r['artistName']} - {r['albumName']}")
            with _save_lock:
                ordered = sorted(results, key=lambda x: x["filename"])
                with open(OUT_JSON, "w", encoding="utf-8") as f:
                    json.dump(ordered, f, ensure_ascii=False, indent=2)

    # keep stable order by filename
    results.sort(key=lambda r: r["filename"])

    with open(OUT_JSON, "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)

    errors = [r for r in results if r["error"]]
    print(f"\nDone. {len(results)} processed, {len(errors)} errors.")
    print(f"Results written to {OUT_JSON}")


if __name__ == "__main__":
    main()
