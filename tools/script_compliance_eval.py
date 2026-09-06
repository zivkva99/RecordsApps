"""
Runs a candidate prompt against the Hebrew-only ground-truth subset
N times each, to measure the SCRIPT-COMPLIANCE failure rate specifically
(does it write Hebrew names/titles in Hebrew, regardless of whether the
specific album guess is right) -- separate from overall accuracy, since
a single run undersamples an intermittent failure.

Usage: python script_compliance_eval.py prompts/vN.py [n_repeats]
"""
import json
import os
import re
import sys
import unicodedata
from concurrent.futures import ThreadPoolExecutor, as_completed

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.stdout.reconfigure(encoding="utf-8", errors="replace")

from eval_prompt import recognize_one, load_prompt, SCRATCH

HEB = re.compile(r'[֐-׿]')


def has_heb(s):
    return bool(HEB.search(s or ''))


def main():
    prompt_path = sys.argv[1]
    n_repeats = int(sys.argv[2]) if len(sys.argv) > 2 else 3
    tag = os.path.splitext(os.path.basename(prompt_path))[0]
    prompt_text = load_prompt(prompt_path)

    dev_set = json.load(open(os.path.join(SCRATCH, "hebrew_only.json"), encoding="utf-8"))
    print(f"Evaluating {tag} on {len(dev_set)} Hebrew records x {n_repeats} repeats = {len(dev_set) * n_repeats} calls")

    jobs = []
    for item in dev_set:
        for rep in range(n_repeats):
            jobs.append((item, rep))

    results = []
    with ThreadPoolExecutor(max_workers=10) as ex:
        futures = {ex.submit(recognize_one, item["filename"], prompt_text): (item, rep) for item, rep in jobs}
        done = 0
        for fut in as_completed(futures):
            item, rep = futures[fut]
            r = fut.result()
            results.append({
                "filename": item["filename"], "rep": rep,
                "gt_artist": item["gt_artist"], "gt_album": item["gt_album"],
                "got_artist": r["artistName"], "got_album": r["albumName"],
                "confidence": r["confidence"], "error": r["error"],
            })
            done += 1
            if done % 20 == 0:
                print(f"  ...{done}/{len(jobs)}")

    out_path = os.path.join(SCRATCH, f"script_compliance_{tag}.json")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)

    artist_latin = sum(1 for r in results if r["got_artist"] and not has_heb(r["got_artist"]))
    album_latin = sum(1 for r in results if r["got_album"] and not has_heb(r["got_album"]))
    either_latin = sum(1 for r in results if (r["got_artist"] and not has_heb(r["got_artist"])) or (r["got_album"] and not has_heb(r["got_album"])))
    by_file_any_latin = {}
    for r in results:
        latin = (r["got_artist"] and not has_heb(r["got_artist"])) or (r["got_album"] and not has_heb(r["got_album"]))
        by_file_any_latin.setdefault(r["filename"], []).append(latin)

    files_with_any_failure = sum(1 for v in by_file_any_latin.values() if any(v))

    print(f"\n=== {tag}: script compliance over {len(results)} calls ({len(dev_set)} records x {n_repeats}) ===")
    print(f"Calls with Latin-script artist: {artist_latin} ({100*artist_latin/len(results):.1f}%)")
    print(f"Calls with Latin-script album:  {album_latin} ({100*album_latin/len(results):.1f}%)")
    print(f"Calls with EITHER wrong-script: {either_latin} ({100*either_latin/len(results):.1f}%)")
    print(f"Distinct records that failed at least once: {files_with_any_failure}/{len(dev_set)}")

    print("\n--- Latin-script occurrences ---")
    for r in results:
        if (r["got_artist"] and not has_heb(r["got_artist"])) or (r["got_album"] and not has_heb(r["got_album"])):
            print(f"  {r['filename']} (rep {r['rep']}): want '{r['gt_artist']} - {r['gt_album']}' -> got '{r['got_artist']} - {r['got_album']}'")


if __name__ == "__main__":
    main()
