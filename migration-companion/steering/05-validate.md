# Phase 5 — Validate

**Goal:** Confirm the migration actually moved what it claimed to,
and that queries return roughly the same results. One pass — don't
build a 5-pass review framework.

Run on paths: `POC`, `MIGRATE`.

## Doc count parity

For each migrated index:

```
GET source/<index>/_count
GET target/<index>/_count
```

Both numbers should match. If they don't, the difference is either:
- An index that wasn't part of the migration scope (re-check the
  index allowlist).
- AOSS truncating `hits.total.value` at 10000 — pass
  `track_total_hits=true`.
- A genuine drop (this is a finding, not a glitch — investigate).

## Sample query parity

For **MIGRATE**: pick 3-5 representative queries with the user.
Don't invent them — ask. They know which queries matter for their
application.

For **POC**: the user didn't bring queries (they're using seeded
data they've never seen). Use 2-3 queries shaped to the seeded
dataset — `match_all`, a `range` on a known timestamp field, a
`term` on a known keyword. The point isn't to validate *their*
workload; it's to show that data round-tripped intact.

For each:

```
POST source/<index>/_search { ...query... }
POST target/<index>/_search { ...query... }
```

Compare:
- `hits.total.value` (with `track_total_hits=true` on target).
- Top-10 doc IDs, in order.
- `_score` drift, **per rank**:
  - Top hit (`hits[0]`): within 5% is normal (BM25 across engines).
  - Mid-rank (`hits[5]` ish): within 10% is normal.
  - Tail (`hits[9]`): can drift more — what matters is whether
    the *same docs* are in the top-10, not their exact scores.
  - If the top-10 doc IDs diverge **in set** (not just order),
    that's a finding. Score-only drift on the same set is fine.
- For ranking-sensitive workloads, compute nDCG@10 against source
  as the reference; flag any query below 0.9.

## Spot-check a few docs

Pick a sample with a real method, not "3 random IDs you made up":

```
# First / last / middle by _doc sort:
POST <index>/_search { "size":1, "sort":[{"_doc":"asc"}] }
POST <index>/_search { "size":1, "sort":[{"_doc":"desc"}] }
# Random sample for billion-doc indices:
POST <index>/_search {
  "size": 10,
  "query": { "function_score": {
    "query": {"match_all": {}},
    "random_score": {"seed": 42, "field": "_seq_no"}
  }}
}
```

Take the IDs that come back, then `GET source/<index>/_doc/<id>` vs
`GET target/<index>/_doc/<id>`. `_source` bytes should be identical
except for `_seq_no`, `_primary_term`, `_version`. If `_source` is
disabled on either side, compare via `GET <index>/_search { "query":
{"ids": {"values":[...]}}, "_source": true }` instead.

## Don't

- Don't build a "claim-trace footer" unless the user asks.
- Don't run a 7-pass review.
- Don't write per-query result files unless something looks wrong
  and you need to attach evidence.

## Exit criteria

You have a one-paragraph verdict: "doc counts match, top-10 hits
match on N/N queries, no drift". Or you have a list of findings.
Either way, move to Phase 6.
