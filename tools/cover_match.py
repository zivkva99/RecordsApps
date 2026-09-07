"""
Runs the app's cover-art matching pipeline (mirrored from
CoverArtMatchService.kt / ItunesCoverArtService.kt) against every record in
examples/recognition_results.json, for bulk QA outside the app: for each
record, searches iTunes for candidate cover art, then asks Gemini to rank
which candidate is the same print edition as the photo.

Usage: python tools/cover_match.py [limit]
Requires: pip install requests Pillow
Reads the Gemini API key from local.properties (gemini_api_key=...).
Writes tools/output/cover_match_results.json and tools/output/cand_thumbs/.
"""

import base64
import io
import json
import os
import re
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from threading import Lock
from urllib.parse import quote

import requests
from PIL import Image

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EXAMPLES_DIR = os.path.join(REPO, "examples")
OUTPUT_DIR = os.path.join(REPO, "tools", "output")
OUT_JSON = os.path.join(OUTPUT_DIR, "cover_match_results.json")
CAND_THUMB_DIR = os.path.join(OUTPUT_DIR, "cand_thumbs")
os.makedirs(CAND_THUMB_DIR, exist_ok=True)

with open(os.path.join(REPO, "local.properties"), "r", encoding="utf-8") as f:
    API_KEY = next(l.split("=", 1)[1].strip() for l in f if l.strip().startswith("gemini_api_key"))

GEMINI_URL = f"https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key={API_KEY}"
MAX_CANDIDATES = 8       # matches Android's MAX_COVER_CANDIDATES
EMBED_CANDIDATES = 4     # how many ranked candidates we keep thumbnails for in the HTML
PHOTO_MAX_DIM = 1024
CAND_MAX_DIM = 500
THUMB_DIM = 220

# Exact prompt copied from CoverArtMatchService.kt PROMPT constant
PROMPT = """You are comparing a photo of a physical vinyl record cover (the first image) against numbered candidate album-art images found online (the images that follow, each preceded by its "Candidate N:" label).

Identify which candidates show the exact same cover artwork and print edition as the photo — same layout, color scheme, and printed text. A different country's reissue, a different color variant, or a generic "same album" image that doesn't match the specific artwork in the photo is NOT a match.

Return ONLY a JSON object:
{
  "rankedCandidates": [<candidate numbers, best match first, every candidate number listed exactly once>],
  "bestIsGoodMatch": true or false
}
"bestIsGoodMatch" must be false unless you are confident the top-ranked candidate is the same print edition shown in the photo."""

session = requests.Session()
_save_lock = Lock()


def itunes_search(artist, album):
    query = quote(f"{artist} {album}")
    url = f"https://itunes.apple.com/search?term={query}&media=music&entity=album&limit={MAX_CANDIDATES}"
    try:
        resp = session.get(url, timeout=10)
        if resp.status_code != 200:
            return []
        results = resp.json().get("results", [])
        urls = []
        for r in results:
            raw = r.get("artworkUrl100", "")
            if raw:
                urls.append(raw.replace("100x100bb", "600x600bb"))
        return urls[:MAX_CANDIDATES]
    except Exception:
        return []


def compress_bytes(raw, max_dim, quality):
    img = Image.open(io.BytesIO(raw)).convert("RGB")
    w, h = img.size
    scale = min(max_dim / w, max_dim / h, 1.0)
    if scale < 1.0:
        img = img.resize((max(1, int(w * scale)), max(1, int(h * scale))), Image.LANCZOS)
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=quality)
    return buf.getvalue()


def download_image(url, max_dim, quality, timeout=10):
    try:
        resp = session.get(url, timeout=timeout)
        if resp.status_code != 200:
            return None
        return compress_bytes(resp.content, max_dim, quality)
    except Exception:
        return None


def parse_cover_match_response(text, candidate_count):
    if candidate_count == 0:
        return {"rankedIndices": [], "bestIsGoodMatch": False}
    try:
        cleaned = text.strip()
        cleaned = re.sub(r"^```json", "", cleaned).strip()
        cleaned = re.sub(r"^```", "", cleaned).strip()
        cleaned = re.sub(r"```$", "", cleaned).strip()
        result = json.loads(cleaned)
        ranked_raw = result.get("rankedCandidates", [])
        seen = []
        for n in ranked_raw:
            idx = int(n) - 1
            if 0 <= idx < candidate_count and idx not in seen:
                seen.append(idx)
        remaining = [i for i in range(candidate_count) if i not in seen]
        return {"rankedIndices": seen + remaining, "bestIsGoodMatch": bool(result.get("bestIsGoodMatch", False))}
    except Exception:
        return {"rankedIndices": list(range(candidate_count)), "bestIsGoodMatch": False}


def call_gemini_rank(photo_path, candidate_bytes_list):
    photo_bytes = compress_bytes(open(photo_path, "rb").read(), PHOTO_MAX_DIM, 85)
    parts = [{"text": PROMPT}]
    parts.append({"inlineData": {"mimeType": "image/jpeg", "data": base64.b64encode(photo_bytes).decode("ascii")}})
    for i, cbytes in enumerate(candidate_bytes_list):
        parts.append({"text": f"Candidate {i + 1}:"})
        parts.append({"inlineData": {"mimeType": "image/jpeg", "data": base64.b64encode(cbytes).decode("ascii")}})

    payload = {
        "contents": [{"parts": parts}],
        "generationConfig": {"thinkingConfig": {"thinkingBudget": 4000}},
    }
    resp = session.post(GEMINI_URL, json=payload, timeout=60)
    resp.raise_for_status()
    data = resp.json()
    cand_parts = data["candidates"][0]["content"]["parts"]
    text = ""
    for p in reversed(cand_parts):
        if not p.get("thought", False) and "text" in p:
            text = p["text"]
            break
    return text


def process_record(rec):
    filename = rec["filename"]
    artist = rec["artistName"]
    album = rec["albumName"]
    photo_path = os.path.join(EXAMPLES_DIR, filename)

    result = {"filename": filename, "candidateUrls": [], "bestIsGoodMatch": False, "error": None}
    try:
        urls = itunes_search(artist, album)
        if not urls:
            return result

        cand_bytes = [None] * len(urls)
        with ThreadPoolExecutor(max_workers=8) as ex:
            futs = {ex.submit(download_image, u, CAND_MAX_DIM, 80): i for i, u in enumerate(urls)}
            for fut in as_completed(futs):
                cand_bytes[futs[fut]] = fut.result()

        available = [(i, b) for i, b in enumerate(cand_bytes) if b is not None]
        if not available:
            result["candidateUrls"] = urls
            return result

        text = call_gemini_rank(photo_path, [b for _, b in available])
        parsed = parse_cover_match_response(text, len(available))
        ranked_original_idx = [available[i][0] for i in parsed["rankedIndices"]]
        remaining = [i for i in range(len(urls)) if i not in ranked_original_idx]
        final_order = ranked_original_idx + remaining

        result["candidateUrls"] = [urls[i] for i in final_order]
        result["bestIsGoodMatch"] = parsed["bestIsGoodMatch"]

        # Save small thumbnails for the top few ranked candidates for embedding.
        for rank, idx in enumerate(final_order[:EMBED_CANDIDATES]):
            b = cand_bytes[idx]
            if b is None:
                b = download_image(urls[idx], CAND_MAX_DIM, 80)
            if b is None:
                continue
            thumb = compress_bytes(b, THUMB_DIM, 65)
            out_path = os.path.join(CAND_THUMB_DIR, f"{os.path.splitext(filename)[0]}__{rank}.jpg")
            with open(out_path, "wb") as f:
                f.write(thumb)
    except Exception as e:
        result["error"] = f"{type(e).__name__}: {e}"
    return result


def main():
    limit = int(sys.argv[1]) if len(sys.argv) > 1 else None
    data = json.load(open(os.path.join(EXAMPLES_DIR, "recognition_results.json"), encoding="utf-8"))
    if limit:
        data = data[:limit]
    print(f"Processing {len(data)} records")

    results = []
    done = 0
    with ThreadPoolExecutor(max_workers=6) as ex:
        futures = {ex.submit(process_record, rec): rec for rec in data}
        for fut in as_completed(futures):
            r = fut.result()
            results.append(r)
            done += 1
            status = "ERR" if r["error"] else ("GOOD" if r["bestIsGoodMatch"] else "UNSURE")
            print(f"[{done}/{len(data)}] {r['filename']:30s} {status:6s} candidates={len(r['candidateUrls'])} err={r['error']}")
            with _save_lock:
                with open(OUT_JSON, "w", encoding="utf-8") as f:
                    json.dump(sorted(results, key=lambda x: x["filename"]), f, ensure_ascii=False, indent=2)

    errors = [r for r in results if r["error"]]
    unsure = [r for r in results if not r["bestIsGoodMatch"] and not r["error"]]
    print(f"\nDone. {len(results)} processed, {len(errors)} errors, {len(unsure)} unsure/no-match.")


if __name__ == "__main__":
    main()
