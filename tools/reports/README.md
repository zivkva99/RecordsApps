# HTML report templates

Both files are Artifact-page templates with a `__DATA_JSON__` placeholder
in their `<script>` tag — replace it with a JSON array to get a
publishable HTML file. Neither script here is committed (they were
one-off), but the pattern is:

```python
data_json = json.dumps(records, ensure_ascii=False)
template = open('crate_index_template.html', encoding='utf-8').read()
final = template.replace('__DATA_JSON__', data_json)
open('crate_index.html', 'w', encoding='utf-8').write(final)
```

- **crate_index_template.html** — one row per record: photo thumbnail,
  original AI read, corrected/confirmed fields (amber-highlighted where
  changed), and the confirmed cover art. Each record needs `filename`,
  `photoThumb` (data URI), `received` / `updated`
  (`{artistName, albumName, year, numRecords, confidence}`),
  `chosenThumb`, `chosenSource` (`'photo'` or `'itunes'`),
  `coverCandidates` (data URIs, ranked), `coverBestIsGoodMatch`. Built
  from `examples/recognition_results.json` + `tools/cover_match.py`'s
  output; also wires up the `db` capability so edits/cover choices made
  in the browser save back to the artifact's database — see
  `artifact-capabilities` for that part if reusing.

- **eval_report_template.html** — one row per record, defaulting to
  showing only the misses: photo, ground truth, and what a candidate
  prompt guessed, with the wrong field in red. Each record needs
  `filename`, `thumb`, `isHebrew`, `gtArtist`, `gtAlbum`, `gotArtist`,
  `gotAlbum`, `artistMatch`, `albumMatch`, `bothMatch`, `confidence`.
  Built from `tools/full_eval.py`'s output JSON.

Both use a fixed `top: 0` sticky table header with a non-sticky toolbar
— an earlier version tried to keep the toolbar sticky too and compute
its height in JS to offset the header below it, which drifted and let
the header overlap a row. Don't reintroduce that; stacking two sticky
elements needs either a reliably fixed-height toolbar or one shared
sticky wrapper, not a measured gap.
