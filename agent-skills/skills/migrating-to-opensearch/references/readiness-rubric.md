# Pitfalls, anti-patterns, and validation

You MUST read this every assessment because the most useful output is sometimes the failure mode this prevents.

## Top 12 anti-patterns

1. **Skipping the OS 2.19 stepping stone before OS 3.x.** You MUST NOT upgrade OS 1.3 / 2.x directly to 3.x because the upgrade path is invalid — cluster MUST reach 2.19 first and deprecated index settings MUST be removed. Retrieve the upgrade-path doc via [`knowledge-retrieval.md`](knowledge-retrieval.md) (Amazon OpenSearch Service (managed) section).

2. **Migrating ES 7.10-or-older indexes into OS 3.x without reindexing.** You MUST NOT migrate ES 7.10-or-older indexes into OS 3.x without reindexing because OS 3.x cannot read those indexes (Lucene 10 incompatibility) even from UltraWarm/Cold. You MUST reindex before upgrade.

3. **Underestimating shard count / using too many shards.** OpenSearch (and ES 7.x) default to 1,000 shards per node (`cluster.max_shards_per_node`). The AWS troubleshooting guide documents that this setting **is tunable** — see "Exceeded maximum shard limit" in the live troubleshooting doc per [`knowledge-retrieval.md`](knowledge-retrieval.md) (Amazon OpenSearch Service (managed) section). **Skill IP**: aim for ≤25 shards/GiB heap and shard sizes 10–50 GiB. Cite the AWS sharding blog — retrieve via [`knowledge-retrieval.md`](knowledge-retrieval.md) (Amazon OpenSearch Service (managed) section).

4. **Treating Serverless NextGen as drop-in replacement.** You MUST NOT treat Serverless NextGen as a drop-in replacement because it does NOT support: custom plugins, manual snapshots, cross-region replication, IVF or Lucene k-NN engine, warmup/stats/training APIs, inline scripts (vector), `_search/scroll`, `_reindex`, custom IDs in time-series/vector collections. Retrieve the serverless general reference via [`knowledge-retrieval.md`](knowledge-retrieval.md) (Amazon OpenSearch Serverless NextGen section).

5. **Forgetting `compatibility.override_main_response_version`.** Some legacy ES OSS clients version-check before connecting; on OpenSearch 1.x / 2.x the cluster reports as ES 7.10 only when this compatibility mode is on, and the default differs between create vs upgrade. **Per the OpenSearch 3.0 breaking-changes file, this setting has been REMOVED in OpenSearch 3.0** — legacy ES clients that depend on it will not work against a 3.x target. You MUST verify the setting on a 1.x/2.x target and plan a client migration before moving to 3.x.

6. **CloudWatch alarms break on rename.** `KibanaHealthyNodes` → `OpenSearchDashboardsHealthyNodes`, `ElasticsearchRequests` → `OpenSearchRequests`, etc. You MUST update before upgrade because otherwise alarms go silent. Retrieve the alarm-rename doc via [`knowledge-retrieval.md`](knowledge-retrieval.md) (Amazon OpenSearch Service (managed) section).

7. **Not enabling `soft_deletes` for CCR.** Indexes created in ES 6.x and upgraded keep `soft_deletes=false` and are not replicable. You MUST reindex.

8. **Mixing hot and warm indexes in manual snapshots.** UltraWarm allows only one warm index per manual snapshot. You MUST NOT mix hot and warm in a single manual snapshot because UltraWarm only restores one warm index per snapshot and mixed payloads will fail. You MUST use the automatic `cs-ultrawarm` repo. Retrieve the UltraWarm doc via [`knowledge-retrieval.md`](knowledge-retrieval.md) (Amazon OpenSearch Service (managed) section).

9. **Assuming k-NN engine portability.** **FAISS has been the default k-NN engine since k-NN 2.18.0.0** (OpenSearch release notes 2.18.0.0); **NMSLIB was deprecated in k-NN 2.19.0.0** (OpenSearch release notes 2.19.0.0 / 3.0 breaking changes). On older OpenSearch / ES sources, ported `dense_vector` indexes may still default to NMSLIB unless an engine is explicitly specified. Lucene engine remains the option for full Lucene-native portability. You MUST NOT assume engine portability because recall and latency vary by engine.

10. **Snapshot version-compat surprises.** Snapshots are forward-compatible by exactly one major version. You MUST NOT attempt to restore ES 1.5 → ES 5.x because the snapshot is two majors apart and unsupported; ES 2.3 → ES 6.x requires intermediate reindex. You MUST plan multi-hop or use Migration Assistant RFS.

11. **Blindly trusting Migration Assistant transformations.** MA auto-transforms `dense_vector` → `knn_vector` and `flattened` → `flat_object`, but does NOT normalize: ES type collisions across indexes, runtime fields (no equivalent), custom analyzers backed by Java factories, ILM policies, Watcher rules, role mappings. You MUST validate every assumption with a sample-restore.

## ES-specific pitfalls

- **Logstash and Beats default distributions** include a license check that fails against open-source OpenSearch. You MUST use Apache 2.0 (OSS) distributions.
- **Curator** only supports legacy `elasticsearch-py` up to 7.13.4. You MUST replace with `opensearch-py`.
- **ES 7.11+ is Elastic License v2 / SSPL.** You MUST flag legal review.
- **Multi-AZ-with-Standby data-copy gotcha**: new indexes MUST have a data-copy count (primaries + replicas) that is a multiple of 3, otherwise migration fails.
- **ES `runtime fields` have no OpenSearch equivalent.** You MUST pre-compute at ingest or use scripted fields (Painless).
- **Watcher / ILM JSON is not portable** to Alerting / ISM. You MUST rebuild.

## OpenSearch upgrade pitfalls

- **k-NN index settings removed in 3.x**: index-level `index.knn.algo_param.{ef_construction, m}`, `index.knn.space_type`, and `knn.plugin.enabled` removed (per breaking-changes file). You MUST move equivalent values to the mapping `parameters` block.
- **NMSLIB engine deprecated in 3.x** (k-NN release notes 2.19.0.0 / breaking-changes 3.0). Not yet removed, but you SHOULD re-create k-NN indexes with FAISS (default since k-NN 2.18.0.0) or Lucene engine.
- **Java Security Manager replaced by a Java agent in 3.x** (driven by JDK 24 permanently disabling the Security Manager). You MUST rework custom security policies.
- **System indexes**: REST API access removed in 3.x (deprecated since 1.x). You MUST use plugin APIs.
- **Romanian analyzer change**: cedilla→comma. You MUST reindex Romanian docs.
- **Performance Analyzer (RCA) agent removed in 3.x**: transition to the Telemetry plugin (OpenTelemetry-based). You MUST migrate the observability stack.
- **SQL plugin in 3.x**: DSL response format removed; `DELETE` statement removed; pagination defaults to Point-in-Time (Scroll API deprecated). You MUST update SQL clients.
- **`compatibility.override_main_response_version` removed in 3.x** — verify legacy ES clients before upgrading to 3.x.

> **Skill IP**: A `LegacyBM25Similarity` → `BM25Similarity` default change in 3.x is sometimes cited but is not present in the current breaking-changes file. You MUST verify the BM25 default matrix in the live doc per [`knowledge-retrieval.md`](knowledge-retrieval.md) before flagging it.

## Validation checklist (before flipping prod traffic)

- [ ] Index counts match between source and target
- [ ] Doc counts within 0.1%
- [ ] Top-N query result-set parity ≥ 95% Jaccard overlap
- [ ] Sample query latencies p50/p99 within 1.2× source
- [ ] Shard health green; no unassigned shards
- [ ] ISM policies migrated and attached
- [ ] Role mappings + SAML/OIDC tested via login flow
- [ ] Saved objects (dashboards, viz, index patterns) imported and rendering
- [ ] CloudWatch alarms updated to new metric names
- [ ] CloudWatch Alarm SNS topics encrypted with KMS (`KmsMasterKeyId`); subscribers verified as authorized personnel
- [ ] CloudTrail enabled and logging OpenSearch Service control-plane API calls
- [ ] VPC Flow Logs enabled on the target domain's subnets (if VPC-deployed)
- [ ] Slow log thresholds configured per index
- [ ] Backup snapshot taken before cutover
- [ ] Client libraries upgraded (`opensearch-py` / `opensearch-java` / `opensearch-rest-client`)
- [ ] All BLOCKING incompatibilities resolved or accepted with explicit owner
- [ ] Cost actuals within 10% of forecast
- [ ] Runbook owner assigned + paged on-call rotation set
- [ ] Source cluster decommission plan + rollback window documented

## Readiness score rubric

The agent computes this score from the workload signals captured during intake and the compatibility scan. Default weights (sum 100):

| Dimension | Weight | Inputs |
|---|---|---|
| Compatibility | 25 | Sum: BLOCKING ×3, HIGH ×2, MEDIUM ×1, LOW ×0.25; capped, then inverted |
| Operational readiness | 15 | Auth-tested? Monitoring? Runbooks? On-call? |
| Sizing fitness | 15 | Instance class matches workload? Shards/heap ratio sane? Hot/warm strategy? |
| Data movement complexity | 15 | Volume, throughput, mutation rate, downtime tolerance |
| Cutover complexity | 15 | Dual-write or rollback plan? |
| Sizing-input completeness | 10 | Pricing inputs known? RI committed? |
| Stakeholder alignment | 5 | Business sign-off? SLA agreed? Training? |

**Tiers**:
- ≥80 → GREEN: proceed; surface top 3 risks
- 60–79 → YELLOW: PoC + spike on weakest dimension
- <60 → RED: do not commit; revisit weakest dimension first

## Citation discipline

Every report MUST cite (URLs catalogued in [`knowledge-retrieval.md`](knowledge-retrieval.md)):
- AWS OpenSearch Service Developer Guide for any sizing/version-migration claim — Amazon OpenSearch Service (managed) section
- Migration Assistant docs when MA is recommended — Migration Assistant section
- AWS Pricing Calculator URL + region + date stamp for any pricing claim — Pricing section

You MUST NOT skip citations because failure to cite is a validation error.
