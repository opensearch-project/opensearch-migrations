# Gap Register Skeleton

Use this table verbatim in section **6. Feature Gap Register** of [report-template](report-template.md). Add one row per incompatibility surfaced by Steps 3, 4, and 6 of the workflow. Severity values come from the canonical rubric in [compatibility-rubric](../references/compatibility-rubric.md) — **BLOCKING / HIGH / MEDIUM / LOW**.

| # | Feature | Solr behavior | OpenSearch alternative | Severity | Effort | Owner action |
|---|---------|---------------|------------------------|----------|--------|--------------|
| 1 | _e.g. eDisMax `mm`_ | _Cross-field minimum-should-match expression_ | `multi_match` + `minimum_should_match` | LOW | S | Translate per [solr-query-behavior-edge-cases](../references/solr-query-behavior-edge-cases.md); validate parity. |
| 2 | _e.g. Custom RequestHandler_ | _Java plugin invoked at query time_ | OpenSearch Search Pipeline (2.9+) or client logic | BLOCKING | L | Rewrite as a search pipeline; smoke-test. |
| 3 | _e.g. Cross-collection join_ | `{!join fromIndex=...}` | Denormalize at index time, or two-query application-side join | BLOCKING | M | Decide denormalize vs join at app layer. |
| 4 | _e.g. TrieIntField_ | Trie-indexed integer (deprecated since Solr 7+ in favor of `IntPointField`) | `integer` field type | MEDIUM | S | Recast values to native JSON numbers per [solr-transformation-rules](../references/solr-transformation-rules.md). |
| 5 | _e.g. Function query `recip()`_ | Score boost via Solr function query | `function_score` with `script_score` (Painless) | MEDIUM | M | Translate; benchmark scoring deltas. |
| 6 | _e.g. cursorMark_ | Solr deep-paging cursor | `search_after` with sort tiebreaker | MEDIUM | S | Update client; deprecate `cursorMark` strings. |
| 7 | _e.g. Spatial `LatLonPointSpatialField`_ | `"lat,lon"` strings | `geo_point` objects | MEDIUM | S | Transform documents at index time. |
| 8 | _e.g. Date math `NOW-1DAY/DAY`_ | Solr date math | OpenSearch `now-1d/d` | LOW | S | Search-and-replace in queries and ISM policies. |

## Severity vocabulary

Severities MUST come from the canonical rubric in [compatibility-rubric.md](../references/compatibility-rubric.md) §1 — BLOCKING / HIGH / MEDIUM / LOW only.

## Effort tiers

- **S** — under 1 engineer-week.
- **M** — 1–4 engineer-weeks.
- **L** — over 4 engineer-weeks; usually requires design review.

## Constraints

- You MUST keep the column order exactly as shown because downstream tooling parses the table by column position.
- You MUST NOT remove a row to "simplify" the report because every flagged incompatibility belongs in the register, even LOW-level, and removed rows hide risk.
- You MUST use the BLOCKING / HIGH / MEDIUM / LOW vocabulary in the Severity column. You MUST NOT use the legacy Breaking / Warning / Info labels because the canonical rubric in [compatibility-rubric](../references/compatibility-rubric.md) uses the four-tier vocabulary, and mixed labels will confuse the agent's downstream consumer.
- You MUST link every row's "OpenSearch alternative" cell to the relevant reference file when one exists.
