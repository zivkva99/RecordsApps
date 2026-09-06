# Recognition prompt tuning — 2026-09

## Method

Built a 100-record dev set from the 181-photo `examples/` corpus (50 known
text corrections + 50 regression-check records already right), then later
a second-round dev set of 73 records (the 33 records the shipped v3 prompt
still got wrong on the full 180-photo corpus + a 40-record regression
sample) once v3 shipped and the corpus's ground truth had stabilized.

`tools/eval_prompt.py <prompt file>` runs a candidate prompt against a dev
set and scores it against ground truth (`examples/recognition_results.json`).
`tools/rescore_eval.py` re-scores a run with punctuation/format-tolerant
fuzzy matching — Gemini's exact wording varies run to run even for an
*unchanged* prompt on a hard cover ("Emerson, Lake & Palmer" vs "Emerson
Lake & Palmer", "Second Helping" vs "Second Keeping"), so strict string
equality is too noisy to compare versions by.

For full-corpus statistics (all 180 photos, one excluded for a known-bad
ground-truth label) with a language × field breakdown, use
`tools/full_eval.py` — the harness this round of tuning added.

## Results

**Fast dev-set scores are noisy** — re-running the *identical* prompt v13
twice gave 50/73 and then 47/73. Read differences under ~3-4 points as
noise, not signal; only full-corpus runs are decisive.

| Prompt | Dev-set (lenient, /73 or /100) | Full corpus (/180) |
|---|---|---|
| v0 (original) | 53/100 | — |
| v1 | 68/100 | — |
| v2 | 71/100 | — |
| v3 (shipped round 1) | 71/100 | 147/180 (81.7%) |
| v4 (Hebrew-script over-firing fix) | 49/73 | — |
| v5-v12 (7 more hypotheses, see below) | 44-49/73 | — |
| v13 (**shipped**) | 47-50/73 | **154/180 (85.6%)** |
| v14-v16 (3 more hypotheses on top of v13) | 44-47/73, all worse | — |

Full-corpus breakdown, v3 → v13:

| | Total | Artist wrong (v3 → v13) | Album wrong (v3 → v13) |
|---|---|---|---|
| Hebrew | 41 | 4 → 6 | 17 → 13 |
| English | 139 | 9 → 6 | 12 → 10 |

Hebrew-artist ticked up slightly (4→6 of 41 — within noise for this sample
size); every other cell improved. Net: 147→154 correct, a genuine gain.

## What actually helped (v1 → v13, in order)

1. **Hebrew script mandate** (v1). The single largest fix: the model often
   identified a Hebrew artist/album correctly but wrote it transliterated
   ("Chava Alberstein") instead of Hebrew ("חוה אלברשטיין").
2. **One title, one script — no duplicates** (v1). Stops appending an
   English gloss/transliteration in parentheses or joining two titles
   with "/".
3. **No confident guessing on the specific album** (v2). The most common
   remaining error: right artist, wrong specific album, especially a
   default self-titled/greatest-hits guess.
4. **No niqqud** (v3). Formatting fix for Hebrew output consistency.
5. **Hebrew rule doesn't apply to Western acts** (v4). A real regression
   found via full-corpus stats that the 100-record dev set had missed
   entirely: on some runs the model Hebrew-transliterated *English* bands
   it was uncertain about — "Genesis" → "ג'נסיס", "Bread" → "בג'ה" — both
   covers 100% Latin text, confirmed by inspecting the actual photos.
   Explicit carve-out fixed both.
6. **No approximate/reconstructed titles** (v13). The clearest remaining
   pattern: wrong answers were often a *real* album title with unstable,
   drifting Hebrew spelling across runs (e.g. three different garbled
   spellings of the same underlying guess for one Yossi Banai cover
   across three prompt versions) — evidence the model was reconstructing
   from a vague memory rather than reading or confidently recalling.
   Telling it to stop doing that measurably helped, with zero regressions
   across two dev-set runs.

## What was tried and did NOT help (v5-v12, v14-v16 — 10 more hypotheses)

- **Broad "don't hallucinate when stuck" instruction** (v5): caused
  garbled off-topic output on a previously-easy call
  ("Genesis - DE 11"). Discarded.
- **Soundtrack/OST artist-attribution rule** (v6, v15): flat to negative.
  Discogs' own convention for film soundtracks is itself ambiguous
  ("Various Artists" vs. the primary band) — not a clear bug to fix.
- **Explicit confidence self-check gate** (v7): zero effect. Across two
  separate attempts (v3's original wording and v7's much more forceful
  "name one piece of evidence" version), **0 of the ~50 wrong answers
  tested were ever marked "low" confidence**. The model has no reliable
  introspective access to "I am confabulating this" — a well-known LLM
  calibration limitation prompting alone doesn't fix.
- **Forcing visible chain-of-thought via an extra JSON field** (v8):
  flat.
- **Removing the (apparently non-functional) confidence section
  entirely** (v9): made things *worse* (45/73) — its presence still
  reinforces a "verify carefully" mindset even though it doesn't achieve
  calibration. Kept.
- **Naming specific look-alike artists to disambiguate** (v10, v14): net
  negative both times, once breaking an easy, previously-correct Pink
  Floyd call. Likely too many competing instructions diluting compliance.
- **Reordering the prompt's sections** (v12, v16): neutral to negative;
  no evidence structure/order matters here.

## What's left (genuine model-knowledge limits, not prompt issues)

The remaining ~26/180 misses are dominated by obscure regional pressings
and generic-looking compilation covers where re-running the *same* prompt
produces a *different* wrong guess each time. The clearest example: a
Derek & The Dominos "Layla" cover (no text at all, just a painting) was
guessed as Renaissance, then Kaveret, then Arik Einstein, then Matti
Caspi, then a Chava Alberstein album across five separate calls with
different prompt versions. That's the model's visual knowledge running
out, not an addressable prompt-clarity problem.

## Round 3: script-compliance rate (v13 → v17, shipped)

The user reported seeing the model recognize a Hebrew artist/album
correctly but still write it in English "on many occasions" — separate
from overall accuracy (v13's headline number was already good), this is
specifically about which *script* a correctly-identified Hebrew record
comes out in.

**A single sample undersamples an intermittent failure.** Measuring
overall accuracy once suggested this was nearly fixed (1/41 Hebrew
records affected in the v13 full-corpus run). To get a real rate,
`tools/script_compliance_eval.py` runs a prompt against
`tools/eval_data/hebrew_only.json` (the 41 Hebrew ground-truth records)
**N times each** and reports the fraction of calls where artistName or
albumName came back in Latin script — independent of whether the specific
album guess is otherwise right or wrong.

| Prompt | Wrong-script rate | Sample |
|---|---|---|
| v13 (baseline) | 3.3% (4/123) | 41 records × 3 |
| v17 (**shipped**) | 0.5%, then 1.0% on repeat | 41 records × 5, twice |
| v18 (+ album-fallback rule) | 2.0% (4/205) — worse | 41 records × 5 |
| v19 (+ narrower album exception) | ~6% (13/205) — much worse | 41 records × 5 |

**What fixed it (v17):** the clearest reproducible case was Esther
Ofarim's self-titled cover, which literally reads "ESTHER OFARIM" in
English block capitals (an international pressing) — the model followed
the cover's printed language 2 of 3 times even though she's Israeli.
Added an explicit rule: the artist's own language identity wins over one
specific pressing's printed language, with that exact cover as the named
example. Confirmed stable across two independent 205-call runs (0.5%,
then 1.0% — both far below baseline, different specific failures each
time, consistent with random noise at a low rate rather than a new bug).

**What made it worse (v18, v19):** v18 tried to also stop the model
substituting an English placeholder title for an unreadable Hebrew one
(targeting a blank Matti Caspi sleeve) — this didn't help that case and
somehow made a different record MORE unstable (a new "Pearl Jam - No
Code" hallucination appeared twice instead of once). v19 tried to carve
out a narrower exception for albumName specifically (reasoning: a
genuine foreign-market compilation like Esther & Abi Ofarim's German
"Die Ofarim-Story" has a real non-Hebrew title, unlike a self-titled
album that just repeats the artist's name) — this backfired badly: the
model applied the new "foreign real title" language to self-titled and
translated-title albums too, and Esther Ofarim's *album* field started
coming back English 5 of 5 times (worse than before the fix), and Danny
Sanderson's Hebrew title leaked its English translation "Life Size" 4 of
5 times. **Lesson: once a targeted fix is working, a second targeted fix
in the same area is high-risk — verify each one in isolation, and revert
immediately rather than layering a second attempt on an unconfirmed one.**

**Known accepted trade-off:** v17 still mis-Hebraizes the one genuine
foreign-title case in the corpus — Esther & Abi Ofarim's German
compilation "Die Ofarim-Story" comes back as a garbled phonetic Hebrew
rendering instead of the real German title. Both v18 and v19's attempts
to fix this specific case made the far more common case (self-titled /
translated Hebrew albums) meaningfully worse, so this one record is left
as a known, accepted edge case rather than chasing it further.

Full-corpus accuracy note: a single v17 full-corpus run scored 149/180
vs v13's 154/180. Diffing the two showed 7 newly-wrong records, of which
6 were unrelated general noise on already-fragile records (same failure
pattern seen fluctuating throughout this whole exercise, no script
involved) and only 1 was the Ofarim German case above. Net: this round
is a real, targeted fix for a real complaint, at the cost of that one
known edge case — not a meaningful accuracy regression.

## Files

- `tools/prompts/current.py` — the shipped prompt (identical to
  `GeminiRecognitionService.kt`'s `PROMPT` constant; verify with a diff
  before trusting either as current). `CoverArtMatchService.kt` uses its
  own separate cover-art-matching prompt, untouched by this round.
- `tools/prompts/v0_baseline.py` — the original prompt, for reference.
- `tools/eval_prompt.py`, `tools/rescore_eval.py` — the dev-set harness.
- `tools/full_eval.py` — full-180-corpus harness with the Hebrew/English ×
  artist/album breakdown.
- `tools/script_compliance_eval.py` — repeated-sampling harness for
  measuring an intermittent-failure *rate* (script compliance, in this
  case) rather than a one-shot pass/fail.
- `tools/eval_data/dev_set.json` — the original 100-record dev set.
- `tools/eval_data/mistake_diffs.json` — the 50 corrections it was built from.
- `tools/eval_data/hebrew_only.json` — the 41 Hebrew ground-truth records
  used by `script_compliance_eval.py`.
