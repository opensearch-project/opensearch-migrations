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

Pick 3-5 representative queries with the user. Don't invent them
— ask. For each:

```
POST source/<index>/_search { ...query... }
POST target/<index>/_search { ...query... }
```

Compare:
- `hits.total.value` (with `track_total_hits=true` on target).
- Top-10 doc IDs, in order.
- Top hit's `_score` to within 5% (BM25 scores can drift slightly
  across engines, that's expected; large drift is a finding).

## Spot-check a few docs

Pick 3 random doc IDs. `GET source/<index>/_doc/<id>` vs
`GET target/<index>/_doc/<id>`. Bytes should be identical except
for `_seq_no`, `_primary_term`, `_version`.

## Don't

- Don't build a "claim-trace footer" unless the user asks.
- Don't run a 7-pass review.
- Don't write per-query result files unless something looks wrong
  and you need to attach evidence.

## Exit criteria

You have a one-paragraph verdict: "doc counts match, top-10 hits
match on N/N queries, no drift". Or you have a list of findings.
Either way, move to Phase 6.
