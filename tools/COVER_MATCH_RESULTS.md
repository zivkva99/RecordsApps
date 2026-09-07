# Cover-art matching: investigation & fix (2026-09-07)

## Task

The app's "does this iTunes cover art actually match your photo?" feature
(`ItunesCoverArtService` + `CoverArtMatchService`) was confirming far fewer
covers than it should. Ran it against all 181 photos in `examples/` to
measure the baseline, diagnosed why, fixed it, and re-measured.

## Baseline (before this round)

`python tools/cover_match.py` against all 181 records:

| | count |
|---|---|
| Confirmed good match (`bestIsGoodMatch: true`) | 37/181 (20%) |
| iTunes search returned zero candidates | 10/181 |
| Candidates found but Gemini rejected all of them | 134/181 |
| Errors | 1/181 |

74% of records got candidates but were rejected — that's where the room to
improve was.

## Root causes (both confirmed by inspecting actual photos + candidate images)

**1. iTunes's free-text search is unreliable for well-known albums.**
Searching `"Pink Floyd The Dark Side of the Moon"` (media=music,
entity=album) does not return the actual studio album *even in the top 25
results* — it's buried behind other Pink Floyd releases and an unrelated
reggae tribute album ("Dub Side of the Moon"). No amount of prompt tuning
can fix a candidate that was never fetched.

Fix: resolve the artist via `entity=musicArtist` search first, then pull
their full album catalog via `/lookup?id=<artistId>&entity=album&limit=200`
and fuzzy-match the target album title against that catalog directly.
Confirmed this reliably surfaces "The Dark Side of the Moon". Kept the old
free-text search too and take the union of both (catalog match first) —
some regional/compilation titles aren't in an artist's main catalog listing
in the way the lookup endpoint returns it, and the text search still
catches some of those.

**2. The matching prompt was rejecting genuine matches.**
For Michael Jackson's Thriller, the actual original-cover candidate *was*
found and ranked #1 by Gemini, but `bestIsGoodMatch` still came back
`false`. The prompt's "exact same print edition" framing was read too
literally — a photo will always have lighting/glare/wear differences from
a clean digital scan, and it was penalizing that.

Fix: reworded the prompt to key on "same front-cover artwork" rather than
"same print edition", explicitly listing what still counts as a match (a
remaster/reissue reusing the same artwork, minor photo conditions, a price
sticker) versus what doesn't (a different photo/illustration, a different
album, a tribute/cover-version release).

## A rate-limiting detour

Fixing #1 roughly tripled the number of iTunes API calls per record (1 →
up to 3). The first full-corpus rerun with naive 1-request/second pacing
got throttled by iTunes partway through and silently produced 97/181
zero-candidate results (a plain non-200 response was being treated as "no
results" rather than "retry"). Fixed by:
- caching each artist's resolved catalog once (many records in a
  collection share an artist — 9 Genesis, 7 Beatles, 6 Elvis, etc. — so
  this also cuts total request volume substantially)
- pacing all iTunes calls through one global rate limiter (~20/min, which
  matches the commonly observed real limit — the 60/min a naive 1s
  interval assumes was still too fast)
- retrying with a real backoff on HTTP 403 instead of treating it as "no
  candidates"

## Result (after both fixes + correct pacing)

Full rerun, all 181 records, 0 errors:

| | count |
|---|---|
| Confirmed good match | **96/181 (53%)** |
| Zero candidates | 9/181 |
| Candidates found but rejected | 76/181 |
| Good-match rate *among records that got candidates* | 56% (was 22%) |

**~2.6x more confirmed cover matches than the baseline** (37 → 96), with no
increase in errors and a roughly flat zero-candidate rate (10 → 9).

Of the 9 remaining zero-candidate cases, 6 are Hebrew-language Israeli
pressings (אילנית, אריאל זילבר, אריק איינשטיין, יגאל בשן, מתי כספי ×2) —
plausibly just not in Apple's catalog at all rather than a search bug; the
other 3 (Gandalf, James Galway, Rare Earth "Band Together") are genuinely
obscure enough that this wasn't independently re-verified.

## What shipped

Both fixes were ported from `tools/cover_match.py` into production:
- `app/src/main/java/com/recordsapp/data/remote/ItunesCoverArtService.kt`
  — added the artist-catalog lookup + fuzzy match (`normalizeAlbumTitle`,
  `titleSimilarity`, `fuzzyMatchCatalog`, `unionCandidates`, unit-tested in
  `ItunesCoverArtServiceTest.kt`), `fetchUrls` now returns the union of
  both search strategies.
- `app/src/main/java/com/recordsapp/data/remote/CoverArtMatchService.kt`
  — updated `PROMPT` to the same reworded version.
- `MAX_COVER_CANDIDATES` in `AddEditAlbumViewModel.kt` raised from 8 to 10
  to match `tools/cover_match.py`'s `MAX_CANDIDATES`.

No pacing/retry infrastructure was ported to the Kotlin side — the rate
limiting only showed up under this tool's rapid bulk testing from one IP;
a real user recognizing one record at a time isn't at meaningful risk of
hitting it.

## Round 2: adding Discogs as a second source (2026-09-07)

The round above still left Hebrew well behind English (41% vs 56% on the
full corpus) and 6 of 41 Hebrew records with zero candidates at all —
iTunes's catalog just doesn't carry many Israeli pressings. Added
[Discogs](https://www.discogs.com) (a vinyl-collector marketplace
database) as a second candidate source, iterated on it with statistics
after each round on an 81-record dev set (all 41 Hebrew + a random 40 of
the 140 English records, for faster iteration than a full 181-record run):

| Iteration | Change | Hebrew | English |
|---|---|---|---|
| 0 (baseline, full corpus) | shipped Round-1 version | 17/41 (41%) | 79/140 (56%) |
| 1 (dev set) | + Discogs as a second source | 22/41 (54%) | 23/40 (58%) |
| 2 (dev set) | + fixed a Discogs image-download bug (below) + blur/angle/lighting-tolerant prompt | 33/41 (80%) | 36/40 (90%) |
| 2, re-run (dev set) | same version, independent re-run to check stability | 36/41 (88%) | 36/40 (90%) |
| 3 (dev set) | + color-cast tolerance in the prompt | 31/41 (76%) | 38/40 (95%) — **reverted** |
| **Final (full 181-record corpus)** | **iteration 2's version** | **30/41 (73%)** | **109/140 (78%)** |

Two bugs found and fixed along the way (both confirmed by testing directly
against the real APIs, not assumed):

1. **Discogs's search endpoint doesn't return image URLs without
   authentication** (`thumb`/`cover_image` come back `""`) — but its
   per-release *detail* endpoint (`/releases/{id}`) does, unauthenticated.
   So: search for release IDs (free), fetch detail for a few of them
   (also free) to get the actual image.
2. **Discogs's combined "artist album" search query silently returns zero
   results for some Hebrew records** — Hebrew grammar glues conjunctions
   directly onto the next word with no space (e.g. "X ומיקי Y" = "X and
   Miki Y" as one token), which doesn't tokenize-match against Discogs's
   index the way a plain artist name does. An album-title-only query still
   finds the record. Fixed by unioning both queries.
3. **(The big one.)** `download_image()` never sent a User-Agent header,
   and Discogs's image CDN (`i.discogs.com`) returns HTTP 403 for the
   default `python-requests` User-Agent — confirmed directly with `curl`.
   This meant *every Discogs candidate image silently failed to download
   during the actual Gemini comparison step* in iteration 1: the search
   was finding the right release (confirmed manually, e.g. for "אריק
   איינשטיין ומיקי גבריאלוב - סע לאט"), but Gemini never actually saw that
   image. Fixing this (iteration 2) was the single largest jump in the
   whole investigation — Hebrew alone went from 54% to 80-88%.

Iteration 3 (a further prompt tweak for photos with a different color
cast than a clean scan) traded a Hebrew regression for an English gain on
the small dev sample, with the numbers close enough to plausibly be
sample noise either way. Followed this session's established rule for an
unconfirmed change stacked on a validated one: reverted rather than risk
it, since Hebrew was the whole point of this round.

**Result: Hebrew 41% → 73% (~1.8x), English 56% → 78% (~1.4x), on the full
181-record corpus.** 1 zero-candidate record remained (down from 10 at the
very start of this work) and 1 transient Gemini API 503 during the run
(unrelated to the matching logic itself).

### What shipped

- New file `app/src/main/java/com/recordsapp/data/remote/DiscogsCoverArtService.kt`
  — mirrors `tools/cover_match.py`'s `discogs_search`; pure helpers
  (`dedupeByMaster`, `pickPrimaryImageUrl`) unit-tested in
  `DiscogsCoverArtServiceTest.kt`.
- `AddEditAlbumViewModel.kt` — fetches iTunes and Discogs candidates in
  parallel (`async`/`awaitAll`) and unions them via the existing
  `unionCandidates` helper; `MAX_COVER_CANDIDATES` raised 10 → 14.
- `CoverArtMatchService.kt` — `PROMPT` updated with the validated
  blur/angle/lighting-tolerance wording (not the reverted color-cast
  addition); `downloadImage()` now sends a `User-Agent` header so Discogs
  candidates actually download during the real app's comparison step too
  (the exact bug above, ported as a fix rather than reproduced).
