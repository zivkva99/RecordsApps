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
Read all text completely and exactly — do not truncate, alter, or replace any word, and do not respell a name phonetically. If you recognize the artist, use their standard, canonical name spelling (in whichever script applies — see below), not an approximation of what the stylized cover lettering looks like.
When reading names in any script, transcribe each word exactly. In Hebrew: ס (samech) and מ (mem) are completely different letters — "סשה" is "Sasha" (S), NOT "Moshe" (which starts with מ). Do not substitute a more famous artist who shares only the surname.
If only the band/artist name is visible with no separate album title, this may be a self-titled album — consider that possibility, especially for bands active in the late 1960s whose debut album shares the band name.

HEBREW RECORDS — SPECIAL CASE
If the cover's text is Hebrew, or the artist is an Israeli / Hebrew-language musician (even one you recognize by a familiar transliterated name, e.g. "Chava Alberstein", "Arik Einstein", "Matti Caspi"), you MUST output BOTH artistName and albumName in Hebrew script (עברית) — never a Latin transliteration. This applies even if the artist is world-famous under an English spelling in your training data: use the standard Hebrew spelling as it would appear on the cover, not the transliteration and not a phonetic respelling. Only fall back to Latin script if the cover text itself is genuinely printed in Latin letters and the artist is not Hebrew-language.

Write Hebrew as plain unpointed text (no niqqud / vowel-point diacritics) — the way titles are normally printed, not the way they'd appear in a prayer book or dictionary.

ONE TITLE, ONE SCRIPT — NO DUPLICATES
Many covers print the same title twice: once in Hebrew, once as an English gloss or transliteration in parentheses or on a second line. Output ONLY the primary title, in the single script chosen above — never append a translation or transliteration in parentheses, and never join two titles with "/". If a cover genuinely combines two distinct album titles (a two-record reissue of two different LPs), pick the one presented as primary/larger, not both joined together.

IDENTIFYING THE ALBUM — DO NOT GUESS A PLAUSIBLE TITLE
Getting the artist right but the specific album wrong is the most common mistake — treat it as seriously as getting the artist wrong.
If prominent text gives you a hypothesis, verify by recalling the actual cover art of that specific album and comparing it to what you see. If the artwork does not match, discard the hypothesis.
Then scan the artist's COMPLETE discography — including debut albums and early 1960s/1970s works — and mentally compare the photographed artwork (background color, pose, objects, photo vs. illustration) against at least three candidate albums by that artist before finalizing, not just the first or most famous one that comes to mind. Artists with many self-titled, numbered, or similar portrait-style covers are the most common source of a wrong-album-right-artist mistake.
Do not default to a self-titled or "greatest hits" guess merely because you recognize the artist but can't place the specific record — that is exactly the situation that calls for "low" confidence, not a confident guess. Only report a specific album as certain when you can recall a concrete, matching visual detail of THAT album's actual cover (not just the artist's general visual style), or when the title is legible on the cover itself.
If no useful text is visible, identify purely from the visual artwork. As a last resort, use your broadest knowledge as if doing a reverse image search.
If multiple song titles are listed on the front cover, this is a compilation — use "low" confidence.

CONFIDENCE — BE HONEST WHEN GUESSING
Mark "high" confidence only if you are certain of the artist AND the exact album AND the year — not merely confident about the artist or the general era.
Recognizing the artist from the cover photo/logo is not the same as knowing which specific album this is — if you had to pick the album from discography knowledge rather than reading it or matching a specific remembered cover, use "low".
If the cover has little or no legible text and you are relying on recalling the artwork alone, use "high" confidence only when at least two independent, specific visual details (e.g. the exact color palette AND a distinctive image/composition detail) match your recollection of that specific album's cover — not just a general vibe or genre match. Otherwise use "low".

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
