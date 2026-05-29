# Timeline & resourcing — skill IP

> **Skill IP** — an operational estimation method, not from any upstream doc. This file owns **calendar timeline** (how long, in weeks) and **resourcing** (engineer-weeks of effort + which roles). It does **NOT** estimate dollars — those route to <https://calculator.aws> (see [`scope.md`](scope.md)). Timeline and effort ARE in scope and are the most-requested output for the Business Stakeholder persona, so every report MUST include them.

The estimate is **deterministic and draft-able from the assessment you already produced** — it composes three things the other steps emit: the recommended migration path + data volume (Step 4), the gap register's effort tiers (Step 3/the gap register), and the readiness tier (Step 6). No retrieval needed. Mark the data-movement duration `[verify]` only when you intend to confirm it against an OpenSearch Benchmark run.

## 1. Effort, in engineer-weeks (from the gap register)

Sum the gap register's per-row effort tiers, then add the standing migration baseline. Effort tiers (same vocabulary as the gap registers):

- **S** — < 1 engineer-week
- **M** — 1–4 engineer-weeks
- **L** — > 4 engineer-weeks (usually a design review)

Standing baseline effort EVERY migration carries (skill IP), independent of the gap register:

| Workstream | Baseline effort | Role |
|---|---|---|
| Target provisioning + IaC (domain, VPC, IAM, KMS, security controls) | 1–2 eng-wk | DevOps / Platform Engineer |
| Migration tooling stand-up (Migration Assistant on EKS / OSI / snapshot repo) | 1–2 eng-wk | DevOps / Platform Engineer |
| Schema/mapping review + overrides (MA auto-translates; you audit) | 0.5–2 eng-wk | Search Relevance Engineer |
| Query-layer re-implementation + relevance validation | 1–4 eng-wk (scales with query complexity / eDisMax tuning) | Search Relevance Engineer |
| Client/library cutover (`opensearch-py`/`-java`, endpoint + auth changes) | 1–3 eng-wk | application team |
| Validation (doc-count + top-N parity + p99) + cutover rehearsal | 1–2 eng-wk | both technical roles |
| Dashboards / alerting / ISM rebuild (if ILM/Watcher/Kibana in scope) | 0.5–2 eng-wk | DevOps / Platform Engineer |

**Total effort = baseline + Σ(gap-register rows).** Express as a range, not a point. State the dominant driver (usually query-layer relevance for search workloads, or the data-movement delta strategy for live-write workloads).

## 2. Calendar timeline, by phase

Calendar duration ≠ effort: phases overlap, and the data-movement and PoC phases are dominated by wall-clock (data transfer, soak time), not engineer hours. Use the standing phase model; pull per-phase data-movement duration from the duration heuristics in [`decision-trees.md`](decision-trees.md).

| Phase | Typical calendar | Gated by |
|---|---|---|
| Assess + sign-off | 1 week | this report + stakeholder go/no-go |
| Provision + tooling stand-up | 1–2 weeks | IaC review, account/region prerequisites |
| PoC + spike (REQUIRED if readiness is YELLOW; spikes the weakest dimension) | 1–3 weeks | the weakest readiness dimension (usually data-movement / cutover) |
| Schema + query rebuild + relevance validation | 2–6 weeks | query complexity; runs parallel to provisioning |
| Backfill (data movement) | `≈ source_data ÷ throughput` per [`decision-trees.md`](decision-trees.md) duration heuristics; MA RFS scales with shard count | data volume; mark `[verify]` until benchmarked |
| Dual-write / delta-close / soak | 1–2 weeks | live-write delta strategy (esp. when no Capture & Replay, e.g. Solr) |
| Cutover + rollback window | 1 week | parity gates green; rollback rehearsed |
| Decommission source | after rollback window | retention policy |

Total calendar is **not** the sum of the column — overlap the rebuild phase with provisioning/backfill. State a realistic end-to-end range (e.g. "≈ 8–12 weeks") and the **critical path** (the phase that actually sets the date — usually PoC + relevance rebuild for search, or backfill for very large datasets).

## 3. Adjust by readiness tier

The readiness tier ([`readiness-rubric.md`](readiness-rubric.md)) sets confidence and the gating:

- **GREEN (≥80)** — quote the timeline as committable; the top-3 risks are manageable in parallel.
- **YELLOW (60–79)** — you MUST front-load a PoC + spike on the weakest dimension and quote the timeline as "after the spike confirms the approach." Do NOT commit a hard cutover date before the spike.
- **RED (<60)** — do NOT quote a delivery date; quote only the spike/PoC duration needed to retire the weakest dimension, then re-assess.

## 4. What to put in the report

- A **phase table** (phase · calendar duration · effort eng-wk · owner role) — this is the core artifact the Business Stakeholder asked for.
- A one-line **total**: end-to-end calendar range + total engineer-weeks + the critical-path driver.
- A **resourcing summary**: how many people of which role (Search Relevance Engineer, DevOps / Platform Engineer, application team), and whether they can be part-time/parallel.
- The **readiness-tier caveat** (committable vs after-spike vs not-yet).

## 5. Guardrails

- You MUST express timeline and effort as **ranges with stated assumptions**, never false-precision point estimates.
- You MUST NOT convert engineer-weeks to dollars — resourcing is headcount-and-duration; cost routes to <https://calculator.aws>.
- You MUST mark the data-movement duration `[verify]` whenever it materially drives the date, and confirm it against an OpenSearch Benchmark run before committing (per [`decision-trees.md`](decision-trees.md) and [`sizing-formulas.md`](sizing-formulas.md)).
- You MUST state the **critical path** so the stakeholder knows which phase to protect.
