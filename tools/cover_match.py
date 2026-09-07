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
MAX_CANDIDATES = 10      # matches Android's MAX_COVER_CANDIDATES
TEXT_SEARCH_CANDIDATES = 6   # from the plain iTunes text search
CATALOG_MATCH_CANDIDATES = 6  # from the artist-catalog fuzzy match
CATALOG_MATCH_CUTOFF = 0.4
EMBED_CANDIDATES = 4     # how many ranked candidates we keep thumbnails for in the HTML
PHOTO_MAX_DIM = 1024
CAND_MAX_DIM = 500
THUMB_DIM = 220

# Exact prompt copied from CoverArtMatchService.kt PROMPT constant
PROMPT = """You are comparing a photo of a physical vinyl record cover (the first image) against numbered candidate album-art images found online (the images that follow, each preceded by its "Candidate N:" label).

Identify which candidates show the same front-cover artwork as the photo: the same photograph/illustration, layout, and title treatment. A later remaster/anniversary/digital reissue that reuses the same front-cover artwork still counts as a match — do not reject it just because it isn't the original vinyl pressing.

Do NOT count as a match: a different photograph or illustration, a different color scheme, a different album entirely (including a tribute/cover-version album by another artist, or a different volume/edition with different content), or a generic "same artist" image that isn't the specific cover shown.

Minor things that do NOT disqualify a match: the photo's lighting/glare/wear, a price sticker or barcode, or a small "remastered"/anniversary badge added on top of the same artwork.

Return ONLY a JSON object:
{
  "rankedCandidates": [<candidate numbers, best match first, every candidate number listed exactly once>],
  "bestIsGoodMatch": true or false
}
"bestIsGoodMatch" should be true whenever the top-ranked candidate's front-cover artwork clearly matches the photo by the rule above. Only mark it false if none of the candidates are a confident match."""

session = requests.Session()
_save_lock = Lock()

# iTunes's search API has a fairly aggressive undocumented per-IP rate limit
# (observed: bursts of concurrent requests start getting HTTP 403 within
# seconds). Serialize all calls to it through one pacer with retry/backoff
# instead of just treating a 403 as "no results" -- that silently produced
# 97/181 zero-candidate records the first time this ran with unpaced calls.
_itunes_lock = Lock()
_itunes_last_call = [0.0]
# iTunes's commonly-observed real limit is ~20 req/min, not the 60/min a 1s
# interval assumes -- that first attempt still got throttled 97/181 times.
ITUNES_MIN_INTERVAL = 3.2  # seconds between requests, global across threads


def _itunes_get(url, timeout=10, max_retries=5):
    resp = None
    for attempt in range(max_retries):
        with _itunes_lock:
            wait = _itunes_last_call[0] + ITUNES_MIN_INTERVAL - time.time()
            if wait > 0:
                time.sleep(wait)
            _itunes_last_call[0] = time.time()
        try:
            resp = session.get(url, timeout=timeout)
        except Exception:
            resp = None
        if resp is not None and resp.status_code == 200:
            return resp
        if resp is not None and resp.status_code == 403:
            time.sleep(15 * (attempt + 1))  # a longer, harder cooldown
            continue
        return resp  # some other error (404 etc.) -- don't retry
    return resp


def itunes_text_search(artist, album, limit=TEXT_SEARCH_CANDIDATES):
    """Plain iTunes free-text search. Simple and usually fine, but iTunes's
    relevance ranking sometimes buries or entirely omits the actual studio
    album (confirmed for e.g. Pink Floyd's "The Dark Side of the Moon", not
    present even in the top 25 results) -- see itunes_catalog_search."""
    query = quote(f"{artist} {album}")
    url = f"https://itunes.apple.com/search?term={query}&media=music&entity=album&limit={limit}"
    try:
        resp = _itunes_get(url)
        if resp is None or resp.status_code != 200:
            return []
        results = resp.json().get("results", [])
        urls = []
        for r in results:
            raw = r.get("artworkUrl100", "")
            if raw:
                urls.append(raw.replace("100x100bb", "600x600bb"))
        return urls[:limit]
    except Exception:
        return []


def _normalize_title(s):
    s = (s or "").casefold().strip()
    s = re.sub(r"\([^)]*\)", " ", s)   # drop "(2007 Stereo Mix)" etc.
    s = re.sub(r"[^\w\s]", " ", s)
    return re.sub(r"\s+", " ", s).strip()


# Many records in a collection share an artist (9 Genesis, 7 Beatles, 6
# Elvis, ...) -- cache each artist's resolved catalog once instead of
# re-fetching it per record, both to cut total request volume and to keep
# this fast despite the pacing above.
_catalog_cache = {}
_catalog_cache_lock = Lock()


def _artist_catalog(artist):
    key = artist.casefold().strip()
    with _catalog_cache_lock:
        if key in _catalog_cache:
            return _catalog_cache[key]
    catalog = []
    try:
        aq = quote(artist)
        ar = _itunes_get(f"https://itunes.apple.com/search?term={aq}&media=music&entity=musicArtist&limit=1")
        if ar is not None and ar.status_code == 200:
            artist_results = ar.json().get("results", [])
            if artist_results:
                artist_id = artist_results[0]["artistId"]
                lr = _itunes_get(f"https://itunes.apple.com/lookup?id={artist_id}&entity=album&limit=200")
                if lr is not None and lr.status_code == 200:
                    albums = [r for r in lr.json().get("results", []) if r.get("wrapperType") == "collection"]
                    for a in albums:
                        name = _normalize_title(a.get("collectionName", ""))
                        raw = a.get("artworkUrl100", "")
                        if name and raw:
                            catalog.append((name, raw.replace("100x100bb", "600x600bb")))
    except Exception:
        pass
    with _catalog_cache_lock:
        _catalog_cache[key] = catalog
    return catalog


def itunes_catalog_search(artist, album, limit=CATALOG_MATCH_CANDIDATES):
    """Fuzzy-matches the target album title against that artist's actual
    album catalog -- sidesteps iTunes's unreliable free-text relevance
    ranking for the album search itself."""
    from difflib import SequenceMatcher
    catalog = _artist_catalog(artist)
    if not catalog:
        return []
    target = _normalize_title(album)
    scored = []
    for name, url in catalog:
        ratio = SequenceMatcher(None, target, name).ratio()
        if ratio >= CATALOG_MATCH_CUTOFF:
            scored.append((ratio, url))
    scored.sort(key=lambda x: -x[0])
    return [url for _, url in scored[:limit]]


def itunes_search(artist, album):
    """Union of both search strategies, deduped, capped to MAX_CANDIDATES."""
    text_urls = itunes_text_search(artist, album)
    catalog_urls = itunes_catalog_search(artist, album)
    seen = set()
    combined = []
    # Interleave so a strong catalog match isn't starved by 6 text results
    # filling the cap first.
    for u in catalog_urls + text_urls:
        if u not in seen:
            seen.add(u)
            combined.append(u)
    return combined[:MAX_CANDIDATES]


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
