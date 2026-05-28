# Hidden nuggets — migration assessment gotchas

The traps experienced practitioners hit. Each one is a real failure mode that silently breaks a migration plan. You MUST cite these in the risk register when the profile matches.

## 1. Serverless rejects custom `_id` PUT/upsert on TIME_SERIES and VECTORSEARCH

Confirmed by `serverless-overview.html` (Choosing a collection type): *"For time series and vector search collections, you can't index by custom document ID or update by upsert requests. This operation is reserved for search use cases."* Idempotent client-keyed pipelines (Solr `uniqueKey`, ES `external` versioning, dedup-by-fingerprint) silently break on `TIME_SERIES`/`VECTORSEARCH`.

**Detect:** mutable / bulk-reindex update pattern AND target is non-`SEARCH` Serverless.
**Fix:** you MUST choose `SEARCH`, OR move to AOS managed, OR refactor the writer to let Serverless auto-generate `_id`. Cite `serverless-overview.html` and `serverless-genref.html` (per-permission caveats).

## 2. OS 2.17 changes the per-node shard cap

Confirmed by `bp-sharding.html`: *"Elasticsearch 7.x and later, and all versions of OpenSearch up to 2.15, have a limit of 1,000 shards per node. To adjust the maximum shards per node, configure the `cluster.max_shards_per_node` setting. For OpenSearch 2.17 and later, OpenSearch Service supports 1000 shards for every 16GB of JVM heap memory up to a maximum of 4000 shards per node."* `managedomains-multiaz.html` further caps Multi-AZ-with-Standby clusters at 1,000 shards per node total. ES 7.x clusters that ran past 1,000/node by raising `cluster.max_shards_per_node` need to verify whether their target heap size accommodates the new heap-proportional rule.

**Detect:** ES/OS source with shards/node > 800.
**Fix:** you MUST consolidate via ISM rollover, denser r6g/r7g (16+ GiB heap), or move to Serverless where sharding is automatic.

## 3. Cold storage is NOT directly queryable

Confirmed by `cold-storage.html`: *"When you need to query cold data, you can selectively attach it to existing UltraWarm nodes."* The same doc states: *"OpenSearch Service migrates one index at a time to cold storage. You can have up to 100 migrations in the queue."* Monitor `WarmToColdMigrationQueueSize`.

**Detect:** "occasional queries on archived data".
**Fix:** you MUST accept warm-up latency (minutes-to-hours), keep data in UltraWarm permanently, or use S3+Athena/OpenSearch direct-query for true on-demand archives.

## 4. Serverless VECTORSEARCH cannot share OCUs with SEARCH/TIME_SERIES

Confirmed by `serverless-scaling.html`: *"A vector search collection can't share OCUs with search and time series collections, even if the vector search collection uses the same KMS key as the search or time series collections. A new set of OCUs will be created for your first vector collection."* Adding one vector collection therefore roughly **doubles** the idle floor.

**Detect:** mixed keyword + vector workload; user assumes one bill.
**Fix:** you MUST project both floors via <https://calculator.aws>. If vector is exploratory, you SHOULD use AOS k-NN on the existing managed cluster.

## 5. Serverless ignores most user-supplied index settings

Confirmed by `serverless-overview.html` (Limitations): *"The number of shards, number of intervals, and refresh interval are not modifiable and are handled by OpenSearch Serverless."* `serverless-overview.html` also notes Serverless does not support manual snapshots, so any "restore to Serverless" path actually rebuilds via reindex/bulk and the underlying layout is whatever Serverless auto-picks. **Skill IP** (operational observation, not in upstream docs): `index.translog.*` and `index.routing.allocation.*` settings are also dropped.

**Detect:** plan involves restoring an existing snapshot to Serverless.
**Fix:** you MUST use Migration Assistant's metadata-migration Serverless-mode sanitizer, or hand-strip index settings before bulk. You MUST re-validate with `GET <idx>/_settings` post-load.

## 6. SigV4 comma-encoding in `_cat` APIs — **Skill IP** (operational observation, not in upstream docs)

Reported behaviour: botocore signs commas raw but the AOS gateway canonicalizes them as `%2C` → 403 "signature does not match" on URLs like `_cat/indices?h=index,docs.count&format=json`. Verify against current `request-signing.html` and the active `requests-aws4auth` / `botocore` versions before committing.

**Detect:** any AOS automation hitting `_cat/*?h=a,b,c`.
**Fix:** `requests-aws4auth(urlencode_querystring=False)`, OR pre-encode `,` → `%2C` before signing AND sending, OR drop `h=` entirely.

## 7. Lucene 9 vs 10 segment-format wall

OS 3.0 release notes confirm OS 3.x ships Lucene 10 (PR [#16366](https://github.com/opensearch-project/OpenSearch/pull/16366)); OS 2.x ships Lucene 9 (the `_nodes` API on a 2.17 domain returns `lucene_version: 9.11.1` — see `troubleshooting-custom-plugins` example in `handling-errors.html`). Because Lucene's on-disk format is forward-only (`IndexFormatTooNewException` is a generic Lucene behaviour, not specific to OpenSearch), a Lucene-10 segment cannot be opened by Lucene 9. You MUST pick the target version *before* taking the snapshot — verify with the migration team if you intend to roll back from OS 3.x to OS 2.x.

## 8. `fielddata: true` in old ES 1.x mappings will OOM AOS data nodes — **Skill IP** (operational observation, not in upstream docs)

Pre-ES 2.0, text fields used in-memory `fielddata` for sort/agg. ES 1.x mappings still carry `"fielddata": true` and we have observed them OOM on first aggregation. Reinforced indirectly by `bp.html`: *"Avoid aggregating on text fields, or change the mapping type for your indexes to `keyword`."*

**Detect:** source = ES 1.x or 2.x; mapping JSON contains `fielddata`.
**Fix:** you MUST strip `fielddata` and add a `.keyword` subfield. Migration Assistant transformer does this automatically; hand-rolled `_reindex` MUST do it explicitly.

## 9. ES 7 → OS 1 `_type` removal

ES 7 still allows the placeholder type `_doc`; OS 1.0 removed types entirely. Templates with `"_doc": {...}` blow up `_reindex`/`_bulk` with `[mapper_parsing_exception] unsupported parameters: [_doc]`.

**Fix:** Migration Assistant metadata transformer, OR pre-flatten: `jq 'del(.mappings._doc) | .mappings = .mappings._doc' template.json`.

## 10. `max_clause_count` differs across the boundary

Confirmed for Elasticsearch 7.x: `indices.query.bool.max_clause_count` defaults to **1024** (Elastic search-settings reference, 7.17). OpenSearch 2.x raises this default — verify the exact OS 2.x value against the live OpenSearch search-settings page rather than embedding a number here. Wildcard- / term-heavy queries can fail either way.

**Detect:** application uses generated `terms`/`should` clauses with unbounded list size.
**Fix:** you MUST cap clauses application-side, OR set `indices.query.bool.max_clause_count` (advanced cluster settings; requires blue/green).

## 11. Cross-AZ replication on Amazon OpenSearch Service is not a customer-visible billing line

Self-managed Elasticsearch on EC2 across multiple AZs pays cross-AZ data-transfer at the standard regional rate for primary→replica replication. `bp.html` states: *"There are no cross-AZ data transfer charges for cluster communications between Availability Zones."* Multi-AZ-with-Standby is also "available at no extra cost" per `managedomains-multiaz.html`. Cross-AZ data-transfer for intra-cluster replication is therefore not a customer-visible line item — verify the customer's bill against AWS pricing if they currently see one.

**Detect:** customer's current TCO model includes a cross-AZ data-transfer line item for self-managed ES replication.

**Fix:** call this out as a **savings the migration unlocks**, not a new cost. Cross-AZ data transfer between **customer-owned resources** (e.g. application tier in customer VPC ↔ managed-domain VPC endpoint, or NAT-Gateway egress) is still billed normally; only the intra-cluster replication is free.

## 12. AOS-managed gp3 storage rate is published separately from raw EBS gp3

`bp.html` notes that gp3 on AOS is "9.6% lower cost than with the previously-offered Amazon EBS gp2 volume type" — the comparison there is gp3-vs-gp2 within AOS, not AOS-managed-gp3 vs raw EBS gp3. The exact AOS-managed gp3 list price (volume + baseline IOPS + service overhead) is published separately on the Pricing Calculator and the OpenSearch Service pricing page; TCO calculators that re-use the raw EBS rate may underestimate. Verify against the live pricing page before quoting.

**Fix:** you MUST plug the AOS-managed gp3 rate from <https://calculator.aws> into the customer's TCO model (RI / Savings Plan / EDP discounts apply only there).

## 13. Serverless redundancy-ON floor is 2 OCUs (1 indexing + 1 search, each 0.5 OCU × 2)

Confirmed by `serverless-overview.html` (Pricing): *"When you create a collection with redundant active replicas, you're billed for a minimum of 1 OCU (0.5 OCU × 2) for ingestion, including both primary and standby, and 1 OCU (0.5 OCU × 2) for search."* Redundancy OFF: minimum 1 OCU (0.5 OCU × 2) for the first collection. The floor is billed even when idle.

**Detect:** bursty/low-volume user thinking "I'll only pay for what I use".
**Fix:** you MUST quote both floors. For truly tiny **non-production / dev-test** workloads, you MAY consider a small managed instance (e.g. `t3.medium.search`) instead of Serverless — but you MUST NOT recommend `t2.*` or `t3.small.search` for production data nodes because their CPU credits exhaust under sustained load (`bp.html`: *"Avoid using T2 or `t3.small` instances for production domains because they can become unstable under sustained heavy load"*). For production, prefer `r6g.large.search` or larger.

## 14. OSI persistent buffering steals OCUs from your declared max

Confirmed by `osis-features-overview.html`: *"OpenSearch Ingestion dynamically determines the number of OCUs to use for persistent buffering, factoring in the data source, streaming transformations, and sink destination. Because it allocates some OCUs to buffering, you might need to increase the minimum and maximum OCUs to maintain the same ingestion throughput."* The same doc says: *"Pipelines with persistent buffer enabled split the configured OCUs (pipeline units) between compute and buffer evenly using a 1:1 buffer-to-compute ratio."* Specific stateful-vs-stateless OCU caps are not stated in the doc — verify the live `serverless-scaling.html` and AOSS quota pages for the current per-pipeline maximum before quoting a number.

**Fix:** you MUST raise `max_units` and pre-test under load. Cite `osis-features-overview.html`.

## 15. NAT Gateway charges on private VPC clusters

A private cluster fetching plugins, Bedrock embeddings, IDP metadata, or external knowledge sources accumulates NAT-Gateway charges that are easy to overlook in the migration sizing model. The Migration Assistant cost guide (`solutions/.../cost.html`) lists NAT Gateway at **$0.045 per hour per AZ + $0.045 per GB processed**. Plug the customer's AZ count and projected egress into <https://calculator.aws> rather than embedding a monthly figure here, because the total varies dramatically with traffic volume.

**Fix:** you SHOULD use VPC endpoints for S3, Bedrock, STS, OpenSearch Service. You MUST project NAT egress at the rates above for the residual traffic.

## 16. UltraWarm addressable capacity is much larger than local SSD

Confirmed by `ultrawarm.html`: *"An `ultrawarm1.large.search` instance can address up to 20 TiB of storage on S3, but if you store only 1 TiB of data, you're only billed for 1 TiB of data."* And: *"each `ultrawarm1.medium.search` instance has two CPU cores and can address up to 1.5 TiB of storage on S3."* The "max addressable warm = 5× cache" heuristic is **Skill IP** (operational rule of thumb, not in the doc) — verify the per-instance addressable storage in the live UltraWarm quotas page rather than scaling by a multiplier.

## 17. OR1 trades indexing throughput for replica simplicity — **Skill IP** (operational observation, not in upstream docs)

OR1 (and OR2/OM2/OI2) stores segments in S3 with local NVMe cache. The "≈ 2× r6g indexing throughput", "replica=1 is enough", and "~40% TCO drop" figures are practitioner observations from real deployments — verify against the current AWS OR1 announcement and pricing before quoting. `petabyte-scale.html` lists `OR1.16xlarge.search` (36 TB storage) as a 10 PB-class option, which is consistent with this profile. OR1 loses to r-family on cache-miss aggregations and k-NN graphs (RAM-bound).

**Fix:** you SHOULD recommend OR1 when `peak_indexing × avg_doc_size > 50 GB/day/node`. Use one replica unless your durability model demands more.

## 18. Manual snapshot S3 storage bills against YOUR bucket

Confirmed by `managedomains-snapshots.html`: *"OpenSearch Service stores automated snapshots in a preconfigured Amazon S3 bucket at no additional charge."* And: *"Manual snapshots ... are stored in your own Amazon S3 bucket and standard S3 charges apply."* Automated snapshots are retained for 14 days (hourly, up to 336). Manual / custom-retention / cross-region snapshots bill at S3 standard rates plus PUT charges.

**Fix:** you MUST add an S3 line to the sizing model: `data_size × retention_days / 30 × $/GB-mo` plus PUT cost.

## 19. Solr → OpenSearch is document-level, NOT segment-level

There is **no segment-level migration**. You MUST NOT copy Solr index files directly because the codec and schema layout differ between Solr and OpenSearch and the segments will not load. All Solr migrations are document-level.

**Detect:** "can I just copy the Solr index files?".
**Fix:** you MUST use Migration Assistant Solr backfill (Reindex-from-Snapshot, RFS) for >1 TB or zero-downtime. For smaller pulls you MAY use Spark + opensearch-spark, Solr `/export`, or SolrJ `cursorMark`. See [`decision-trees.md`](decision-trees.md).

## 20. Serverless does not support manual `_snapshot` repositories

Confirmed by `serverless-overview.html` (Limitations): *"Automated snapshots are supported for OpenSearch Serverless collections. Manual snapshots are not supported."* The same Limitations list also says: *"There's currently no way to automatically migrate your data from a managed OpenSearch Service domain to a serverless collection. You must reindex your data from a domain to a collection."* You therefore MUST NOT bring an existing snapshot directly into Serverless. Available paths: Migration Assistant (Serverless-mode sanitizer), OSI pull pipelines, Logstash, or direct application bulk.

**Fix:** you SHOULD use Migration Assistant Metadata + RFS to *managed* AOS, validate, then reindex-from-remote into Serverless — or skip the hop and use OSI's S3 source.

## 21. Snapshot/Restore is NOT safe from ES ≥ 7.11 → OpenSearch (post-fork)

ES 7.10.2 was the OpenSearch fork point. ES 7.11+ (including 7.17 and all 8.x) runs under ELv2/SSPL. **Snapshot/Restore from ES ≥ 7.11 to OpenSearch is NOT a supported migration path on Amazon OpenSearch Service.** Use Migration Assistant Reindex-from-Snapshot (RFS) or `_reindex` from remote instead. Verify the current Migration Assistant source-engine matrix in [`knowledge-retrieval.md`](knowledge-retrieval.md) (Migration Assistant section) before quoting versions.

**Detect:** source ES version ≥ 7.11 AND recommended path is Snapshot/Restore.

**Fix:** you MUST NOT recommend Snapshot/Restore from ES ≥ 7.11 to Amazon OpenSearch. Use Migration Assistant RFS (any volume) or `_reindex` from remote (small / both-ends-online clusters); for zero-downtime use Migration Assistant Capture & Replay. ES ≤ 7.10.2 → OS Snapshot/Restore is fine because that's pre-fork.

## 22. `_source: false` indexes — RFS reads at the segment level, other tools read `_source`

When `_source` is disabled on a source index (a common disk-saving pattern on log-heavy clusters: `"_source": {"enabled": false}` in the mapping), the original document is **not stored** — only the inverted index, doc-values, and any explicitly `store: true` fields.

Migration Assistant RFS reads at the **Lucene segment level** (confirmed by `RFS/docs/SNAPSHOT_READING.md` in `opensearch-project/opensearch-migrations`, which describes unpacking shard data via the Lucene library and reconstructing fields from `_0.cfs` / `_0.cfe` / `_0.si` / stored-field files). Other paths (`_reindex` from remote, OSI / Data Prepper, Logstash `elasticsearch` input) read documents through the source `_search` API which surfaces `_source`, and may yield empty or partial documents on indexes with `_source.enabled: false` — verify with the migration team before committing to those paths. Snapshot/Restore preserves the on-disk format, so the target inherits the same `_source: false` mapping and any downstream reindex hits the same wall.

**Detect:** check the source-index mapping for `"_source": {"enabled": false}`. Common on logs / metrics / time-series indexes that pre-date the 1 TB / day cost-conscious era. You MUST verify with `GET <index>/_mapping` during intake.

**Fix:** if `_source` is disabled on any index in scope, prefer Migration Assistant RFS (Solr or Elasticsearch backfill workflow as appropriate) regardless of cluster size; treat alternative paths as needing migration-team confirmation. After cutover, you SHOULD enable `_source` on the target index because the storage savings are dwarfed by the operational pain `_source: false` causes (no reindex, no update API, broken `_update_by_query`, breaking partial highlights).
