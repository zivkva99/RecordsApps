# Recognition prompt tuning — 2026-09

## Method

Built a 100-record dev set from the 181-photo `examples/` corpus:
- **50 "mistake" records** — every record whose text needed correcting after
  the initial recognition pass (see `tools/eval_data/mistake_diffs.json`),
  minus one whose ground truth was itself later found to be a mislabel.
- **50 "regression" records** — a random sample of records the original
  prompt already got right, to catch a prompt change that fixes Hebrew
  cases but breaks something else.

`tools/eval_prompt.py <prompt file>` runs a candidate prompt against the
dev set and scores it against ground truth. Because Gemini's exact wording
varies run to run even for an unchanged prompt on a hard cover (`Emerson,
Lake & Palmer` vs `Emerson Lake & Palmer`, `Second Helping` vs `Second
Keeping`), a strict string-equality scorer is too noisy to compare
versions by — `tools/rescore_eval.py` re-scores an eval run with
punctuation/format-tolerant fuzzy matching (drops parentheticals, unifies
"&"/"and", ignores case/nikud) so the number tracks real identification
accuracy, not formatting variance.

## Results (lenient score, /100)

| Prompt | Overall | Mistakes fixed | Regressions broken |
|---|---|---|---|
| v0 (original) | 53 | 6/50 (re-run noise) | 3/50 (re-run noise) |
| v1 | 68 | 21/50 | 3/50 |
| v2 | 71 | 23/50 | 2/50 |
| v3 (**shipped**) | 71 | 23/50 | 2/50 |

v2→v3 is a wash on this metric but a real quality fix (stripped Hebrew
niqqud/vowel-points that were leaking into some responses — not something
this scorer detects, but wrong for a clean database field).

## What the prompt changes fixed

1. **Hebrew script mandate.** The single largest failure category: the
   model correctly identified the artist/album but wrote the name in Latin
   transliteration ("Chava Alberstein") instead of Hebrew ("חוה
   אלברשטיין"). Added an explicit rule: if the record is Hebrew-language,
   output both fields in Hebrew script, even for artists the model
   "knows" by an English spelling.
2. **One title, one script — no duplicates.** The model often appended an
   English gloss or transliteration in parentheses, or joined two titles
   with "/" (`"סע לאט (Sa Le'at)"`, `"Or / Light"`). Told it to pick one
   script and one title.
3. **No confident guessing on the specific album.** The most common
   remaining error after (1) and (2): right artist, wrong specific album
   — especially a default self-titled/greatest-hits guess when the model
   recognized the artist but not the record. Added an explicit
   instruction not to default to a plausible-sounding title, and to use
   "low" confidence when the album is inferred from discography
   knowledge rather than read or visually matched.
4. **No niqqud.** Minor formatting fix for Hebrew output consistency.

## What's left (genuine model-knowledge limits, not prompt issues)

The remaining ~27/100 dev-set misses are dominated by obscure regional
pressings (small Israeli labels, generic-looking soft-rock compilation
covers) where re-running the *same* prompt produces a *different* wrong
guess each time — e.g. the Derek & The Dominos "Layla" cover (no text at
all, just a painting) was guessed as Renaissance, then Kaveret, then
Arik Einstein, then Matti Caspi across four separate calls. That's the
model's visual knowledge running out, not an addressable prompt-clarity
problem — no further prompt wording moved this category in testing.

## Files

- `tools/prompts/current.py` — the shipped prompt (mirrors
  `GeminiRecognitionService.kt` / `CoverArtMatchService.kt` continues to
  use its own cover-art-matching prompt, untouched by this round).
- `tools/prompts/v0_baseline.py` — the original prompt, for reference.
- `tools/eval_prompt.py`, `tools/rescore_eval.py` — the harness.
- `tools/eval_data/dev_set.json` — the 100-record dev set.
- `tools/eval_data/mistake_diffs.json` — the 50 corrections it was built from.
