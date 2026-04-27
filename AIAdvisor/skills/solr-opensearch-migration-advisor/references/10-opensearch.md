# OpenSearch Best Practices for Solr Migrations

This document captures OpenSearch best practices specifically in the context of migrating from Apache Solr.
Each section is framed around a concrete Solr concept or behavior and explains how to correctly replicate,
replace, or improve upon it in OpenSearch.

Sources: [AWS Operational Best Practices](https://docs.aws.amazon.com/opensearch-service/latest/developerguide/bp.html), [OpenSearch Documentation](https://opensearch.org/docs/latest/), [AWS Shard Sizing Guide](https://docs.aws.amazon.com/opensearch-service/latest/developerguide/bp-sharding.html)

---

## Schema Migration: From schema.xml to OpenSearch Mappings

- **Always define explicit mappings before indexing.** Solr requires a schema; OpenSearch allows dynamic mapping but it should never be used in production. Set `"dynamic": "strict"` to replicate Solr's schema-enforced behavior and prevent accidental field creation.
- **Replace `copyField` with `copy_to`.** Solr's `<copyField source="title" dest="text"/>` becomes a `copy_to` property on the source field in the OpenSearch mapping. The destination field does not need to be stored — it exists only for search.
- **Map `StrField` to `keyword`, `TextField` to `text`.** Solr's `StrField` is an exact-match, non-analyzed type — the direct equivalent is `keyword`. `TextField` with an analyzer maps to `text`. Do not use `text` for fields that are only filtered, sorted, or aggregated.
- **Add a `.keyword` sub-field to `text` fields that need sorting or faceting.** Solr allows sorting on a `TextField` via `docValues`; OpenSearch requires a `keyword` sub-field. Define `text` fields with a `fields: { keyword: { type: keyword } }` sub-field to support both full-text search and sort/aggregation.
- **Replace `docValues="false"` fields carefully.** Solr lets you disable docValues per field; in OpenSearch, `doc_values` is enabled by default for most types. Explicitly disabling it saves disk but breaks sorting and aggregations on that field — only disable it for fields that are truly never sorted or aggregated.
- **Use index templates to enforce mapping consistency.** Solr uses `managed-schema` as a single source of truth. In OpenSearch, use index templates to apply the same mapping and settings to every index that matches a naming pattern, replicating that centralized control.
- **Avoid mapping explosions from dynamic Solr fields.** Solr's `dynamicField` patterns can generate many fields at runtime. In OpenSearch, use `dynamic_templates` to control how unmapped fields are handled, and set `"dynamic": "strict"` on the root mapping to prevent unbounded field growth.
- **Do not use the `string` field type.** `string` was a Solr/Elasticsearch 1.x concept and does not exist in OpenSearch. Use `text` for analyzed full-text fields and `keyword` for exact-match fields.
- **Migrate Trie field types to Point field equivalents.** `TrieIntField`, `TrieLongField`, `TrieFloatField`, `TrieDoubleField`, and `TrieDateField` are deprecated in Solr 7+ and have no equivalent in OpenSearch. Map them to `integer`, `long`, `float`, `double`, and `date` respectively.
- **Flag `solr.ICUCollationField`, `solr.EnumField`, `solr.ExternalFileField`, and `solr.PreAnalyzedField` as unsupported.** These field types have no direct OpenSearch equivalent and require manual workarounds or redesign before migration can proceed.
- **Translate Solr stored-only fields carefully.** Solr stores fields individually and can return a stored field without indexing it. OpenSearch stores the original `_source` document. Fields marked `stored="true"` but `indexed="false"` in Solr are retrievable via `_source` in OpenSearch, but `_source` filtering must be configured explicitly if only a subset of fields should be returned.

---

## Solr Collections vs. OpenSearch Indexes and Sharding

- **A Solr collection maps to an OpenSearch index.** Each Solr collection with its own schema becomes a separate OpenSearch index with its own mapping. There is no direct equivalent to Solr's collection aliases that span multiple schemas — use OpenSearch aliases for routing and rollover instead.
- **Translate Solr shard count directly, but validate against size targets.** Solr lets you set `numShards` at collection creation. Use the same count as a starting point, then verify each shard will be 10–30 GiB for search workloads or 30–50 GiB for write-heavy workloads. Resize if needed.
- **Make shard count a multiple of the data node count.** This ensures even distribution and prevents hot nodes. For example, 12 primary shards work well with 2, 3, 4, 6, or 12 data nodes.
- **Limit shards per node.** Aim for no more than 25 shards per GiB of JVM heap. A node with 32 GiB of heap should hold no more than 800 shards. OpenSearch 2.17+ supports up to 4,000 shards per node; earlier versions cap at 1,000.
- **Solr replicas map to OpenSearch replicas, but the model differs.** Solr has NRT, TLOG, and PULL replica types. OpenSearch has a single replica type that is always NRT. Always configure at least one replica; use two replicas for Multi-AZ deployments so each AZ holds a full copy.
- **There is no ZooKeeper in OpenSearch.** SolrCloud uses ZooKeeper for cluster coordination. OpenSearch uses its own internal cluster manager (formerly called master node). Dedicated cluster manager nodes replace the ZooKeeper ensemble — use three for production quorum.
- **Solr's `numShards` is immutable after collection creation — plan carefully.** The same constraint applies in OpenSearch: primary shard count is fixed at index creation. Use the `_shrink` API to reduce shard count on an existing index (requires making it read-only first), or the `_split` API to increase it. Neither is a substitute for correct initial sizing.
- **Use `_rollover` with ISM for time-series collections.** Solr time-series deployments often use date-stamped collection names managed by scripts. In OpenSearch, use ISM rollover policies with a write alias — ISM creates new indexes automatically when size or age thresholds are met, with no manual intervention.
- **Avoid over-sharding small indexes.** If an index holds only a few GiB of data, a single shard is correct — parallelism only helps when shards are on separate nodes with sufficient data. A common Solr migration mistake is carrying over a high `numShards` value from a large Solr collection into a smaller OpenSearch index.

---

## Analysis and Text Processing Migration

- **Verify every custom analyzer with the `_analyze` API before indexing.** Solr's `<analyzer>` chains do not map 1:1 to OpenSearch. After converting, use `POST /<index>/_analyze` with representative sample text to confirm tokenization and filtering behavior matches the Solr original.
- **Match index analyzer and search analyzer.** Solr allows separate `<analyzer type="index">` and `<analyzer type="query">` blocks. OpenSearch supports the same via `analyzer` and `search_analyzer` on the field mapping. Mismatched analyzers are a common source of zero-result queries after migration.
- **Migrate `synonyms.txt` to a search-time synonym filter.** Apply synonyms only in the `search_analyzer`, not the index analyzer. This means synonym lists can be updated by closing and reopening the index — no reindex required. Index-time synonyms require a full reindex on every change.
- **Replace Solr `EdgeNGramFilterFactory` with OpenSearch `edge_ngram` token filter or `search_as_you_type` field type.** For autocomplete, `search_as_you_type` is the preferred approach — it automatically creates the required n-gram and shingle sub-fields without manual analyzer configuration.
- **Do not use `wildcard` or `regexp` queries as a substitute for n-gram analysis.** Solr migrations sometimes replace missing n-gram analysis with `wildcard` queries. These scan every term in the inverted index and cause severe latency at scale. Implement `edge_ngram` analysis at index time instead.
- **Define custom analyzers in index settings, not node configuration.** Solr stores analyzer configuration in `schema.xml` per collection. In OpenSearch, analyzer definitions belong in the `settings.analysis` block of the index. Avoid node-level `opensearch.yml` analysis config — it is not portable across clusters.
- **Apply `lowercase` filter consistently in both index and search analyzers.** A common migration mistake is applying `lowercase` at index time but not at search time (or vice versa), producing case-sensitive mismatches that are hard to diagnose.
- **Migrate Solr `ICUCollationField` manually.** There is no direct OpenSearch equivalent. Use the `icu_collation_keyword` field type from the OpenSearch ICU Analysis plugin, which provides locale-aware collation for sorting and range queries on text fields.
- **Migrate Solr language-specific analyzers to OpenSearch built-in language analyzers.** OpenSearch ships with built-in analyzers for most major languages (e.g. `english`, `french`, `german`). Map each Solr language-specific `TextField` to the corresponding OpenSearch language analyzer. Verify stemming and stop word behavior matches expectations.
- **Test stop word lists explicitly.** Solr and OpenSearch may ship with different default stop word lists for the same language. After migration, test queries containing common stop words to verify they are handled consistently. Provide an explicit stop word list in the analyzer definition rather than relying on defaults.

---

## Query Translation and Relevance

- **Expect BM25 scores to differ from Solr's TF-IDF scores.** OpenSearch defaults to BM25 (`k1=1.2`, `b=0.75`); Solr prior to version 6.0 defaulted to classic TF-IDF. Score values will change — this is expected and generally improves relevance. Re-validate any application logic that uses raw score thresholds (e.g. "only show results with score > 0.5").
- **Replace Solr `fq` (filter query) with `bool.filter` context.** Solr's `fq` parameters are cached and do not affect scoring — the direct equivalent is a clause in `bool.filter`. Do not put filter criteria in `bool.must`; that would affect scoring unnecessarily and bypass the filter cache.
- **Replace eDisMax pf/pf2/pf3 phrase boosts by adding a multi_match phrase query or match_phrase query as a should clause.** There is no direct equivalent to Solr's phrase field boosting. Approximate it by adding a `multi_match` query with `type: phrase` in a `bool.should` clause alongside the main query.
- **Replace eDisMax `bq`/`bf` additive boosts with `function_score` or `script_score`.** Solr's boost queries are additive; OpenSearch's `function_score` is multiplicative by default. Use `boost_mode: sum` to replicate additive behavior, or `script_score` for full control.
- **Replace Solr `cursorMark` deep pagination with `search_after`.** Both use a sort-based cursor, but the syntax differs. `search_after` requires a stable sort with a tiebreaker (typically `_id`). Do not raise `index.max_result_window` as a workaround for deep pagination — it causes heap pressure.
- **Replace Solr facets with OpenSearch aggregations.** Solr `facet.field` maps to a `terms` aggregation; `facet.range` maps to a `range` or `date_histogram` aggregation; pivot facets map to nested `terms` aggregations. Always aggregate on `keyword` fields, not `text` — aggregating on `text` requires `fielddata` and exhausts heap.
- **Replace `{!collapse}` field collapsing with the `collapse` parameter.** OpenSearch supports field collapsing via the `collapse` parameter in the search request. The syntax differs from Solr but the semantics are equivalent for most use cases.
- **Use `simple_query_string` instead of `query_string` for user-facing search.** Solr's default query parser exposes Lucene syntax to users. `query_string` does the same in OpenSearch and is vulnerable to parse errors and query injection. `simple_query_string` is lenient and safe for user input.
- **Tune BM25 `k1` and `b` per field to approximate Solr relevance behavior.** For short fields (titles, product names), lower `b` (e.g. `0.3`) reduces length normalization. For long documents, the default `b=0.75` is appropriate. Configure per-field similarity in the index mapping using the `similarity` setting.
- **Replace Solr `{!geofilt}` and `{!bbox}` with `geo_distance` and `geo_bounding_box` queries.** The spatial query types are functionally equivalent but parameter names differ. Map `LatLonPointSpatialField` to the `geo_point` field type in OpenSearch.
- **Flag Solr-only query parsers with no OpenSearch equivalent.** `{!join}`, `{!graph}`, `{!surround}`, `{!complexphrase}`, and Streaming Expressions have no direct OpenSearch equivalent. These require application-layer redesign and must be flagged as blockers before migration proceeds.
- **Replace Solr `hl` (highlighting) with OpenSearch `highlight`.** OpenSearch supports highlighting via the `highlight` parameter in the search request. The `unified` highlighter (default) and `fvh` (fast vector highlighter, requires `term_vector: with_positions_offsets` on the field) are the main options. Map Solr's `hl.fl`, `hl.snippets`, and `hl.fragsize` to the equivalent OpenSearch highlight field settings.
- **Replace Solr `spellcheck.q` with OpenSearch suggesters.** Solr's spellcheck component operates on the query string. In OpenSearch, use the `term` suggester for single-term corrections or the `phrase` suggester for phrase-level did-you-mean suggestions. Configure the suggester on the same field used for search to ensure suggestions are drawn from the actual index vocabulary.
- **Replace Solr `mlt.fl` field list in MoreLikeThis with `fields` in the `more_like_this` query.** The field list that drives MLT similarity must be explicitly specified in both Solr and OpenSearch. Verify the fields are indexed (not just stored) and that the analyzer produces meaningful terms for similarity comparison.
- **Replace Solr `group.field` result grouping with `collapse` or `terms` aggregation.** Solr's result grouping (`group=true&group.field=category`) returns grouped result sets. OpenSearch's `collapse` parameter returns the top document per group inline in the hits. For full grouped result sets with counts, use a `terms` aggregation combined with `top_hits`.
- **Validate date math syntax differences.** Solr date math uses `NOW`, `+1DAY`, `/DAY` syntax. OpenSearch date math uses `now`, `+1d`, `/d` (lowercase, single-letter units). Update all date range queries and ISM policy conditions that use date math expressions.
- **Replace Solr atomic updates with OpenSearch partial document updates.** Solr supports atomic field-level updates (`set`, `add`, `remove`, `inc`) via the update API. OpenSearch supports partial updates via `POST /<index>/_update/<id>` with a `doc` body, or via `script` for increment/decrement operations. The semantics are similar but the API shape differs.

---

## Sizing the OpenSearch Cluster for a Solr Workload

- **Use Solr's current index size as the baseline, not the raw data size.** Solr's index size on disk (visible in the Admin UI under Core Admin → Statistics) is the best proxy for the OpenSearch index size. The source-to-index ratio is typically around 1:1.1, but can vary. Index a sample dataset in OpenSearch and measure the actual ratio before finalizing node sizing.
- **Account for replica overhead in storage calculations.** Solr NRT replicas store a full copy of the index. OpenSearch replicas do the same. Total storage required = (primary index size) × (1 + number of replicas). Add 25% headroom for merges and growth.
- **Translate Solr JVM heap sizing to OpenSearch heap sizing.** Solr typically runs with 8–32 GiB of heap. Use the same range for OpenSearch data nodes, capped at 32 GiB (the compressed OOPs boundary). Do not exceed 50% of available RAM — the remainder is needed for the OS file system cache.
- **Map Solr node roles to OpenSearch node roles.** A SolrCloud deployment with dedicated overseer nodes maps to OpenSearch dedicated cluster manager nodes. Solr query nodes (PULL replicas) map to OpenSearch coordinating-only nodes. Solr indexing nodes map to OpenSearch data nodes with ingest role enabled.
- **Use Graviton-based instances for new OpenSearch deployments.** Solr typically runs on general-purpose x86 instances. For Amazon OpenSearch Service, Graviton-based instances (r6g, m6g, c6g and newer) deliver better price/performance. Avoid T2 and t3.small — they use burst CPU credits and become unstable under sustained load.
- **Benchmark query latency against Solr before cutover.** Run the same query workload against both Solr and OpenSearch and compare p50, p95, and p99 latencies. OpenSearch query latency should be comparable or better for equivalent hardware. Significant regressions indicate a sharding, mapping, or query translation issue that must be resolved before cutover.
- **Plan for the initial reindex throughput requirement.** The time to migrate data from Solr to OpenSearch depends on document count, document size, and OpenSearch write throughput. Estimate: (total document count × average document size) / (bulk indexing throughput per node × number of data nodes). Run a throughput benchmark on a representative sample before committing to a migration timeline.

---

## Replacing Solr-Specific Features

- **Replace `UpdateRequestProcessorChain` with OpenSearch ingest pipelines.** Solr's update processor chains run logic before a document is committed. OpenSearch ingest pipelines are the direct equivalent — use processors for field renaming, type coercion, enrichment, and conditional logic. Test pipelines with `POST /_ingest/pipeline/<id>/_simulate` before deploying. Set `index.default_pipeline` on the index so every document is processed consistently regardless of whether the client specifies a pipeline.
- **Replace Solr `DIH` (Data Import Handler) with an external ingest tool.** DIH has no equivalent in OpenSearch. Use Data Prepper, Logstash, or a custom application to pull data from the source system and write to OpenSearch via the Bulk API.
- **Replace Solr `MoreLikeThis` handler with the `more_like_this` query.** The MLT query is available in OpenSearch but parameter names differ (`minDocFreq` → `min_doc_freq`, `minTermFreq` → `min_term_freq`). Verify the field list and thresholds produce equivalent results after migration.
- **Replace Solr `SpellCheckComponent` with OpenSearch suggesters.** Use the `term` suggester for did-you-mean corrections or the `phrase` suggester for phrase-level suggestions. The `completion` suggester handles autocomplete use cases.
- **There is no equivalent to Solr Streaming Expressions.** Streaming Expressions have no OpenSearch counterpart. Move aggregation and export logic to the application layer or use OpenSearch aggregations for analytics use cases.
- **Replace `{!join}` cross-collection joins with nested documents or application-side joins.** OpenSearch has no cross-index join. For parent-child relationships, use the `nested` field type (when inner object identity matters) or the `join` field type (for frequently updated child documents). For simple lookups, denormalize at index time.
- **Replace Solr `QueryElevationComponent` with pinned queries.** Use the `pinned` query type in OpenSearch to promote specific document IDs to the top of results, replicating Solr's editorial boosting behavior.
- **Replace Solr block join queries (`{!parent}`, `{!child}`) with `nested` queries.** Solr's block join indexes parent and child documents as a contiguous block. In OpenSearch, use the `nested` field type and `nested` query to achieve the same correlated inner-hit behavior.
- **Replace Solr solrconfig.xml request handler defaults with index-level settings, search templates, or application-layer logic.** Since OpenSearch has no server-side configuration for default query parameters (like rows, fl, or sort), these must be moved to the application layer, encoded directly in the query body, or managed via Search Templates.

---

## Security Migration from Solr

- **Replace Solr Basic Auth with OpenSearch Security plugin FGAC.** OpenSearch's Security plugin (bundled) provides index-level, document-level, and field-level access control — significantly more granular than Solr's Basic Auth. Map Solr roles to OpenSearch roles and role mappings.
- **Replace Solr Kerberos authentication with OpenSearch Security Kerberos domain.** OpenSearch Security supports Kerberos via the `kerberos` authentication domain in `config.yml`. The configuration differs from Solr's but the protocol is the same.
- **Replace Solr PKI authentication with OpenSearch TLS client certificates.** Configure node-to-node and client TLS in `opensearch.yml`. The Security plugin handles certificate-based authentication via the `clientcert` authentication domain.
- **Deploy within a VPC.** Solr is typically deployed on private networks. Replicate this in OpenSearch by placing the domain inside a VPC — this eliminates public internet exposure and adds a network-level security layer on top of FGAC.
- **Enable encryption at rest and node-to-node encryption.** Solr does not encrypt data at rest by default. Enable both for all OpenSearch domains that handle sensitive or regulated data. Use AWS KMS-managed keys for encryption at rest.
- **Enable audit logging.** OpenSearch audit logs (available with FGAC enabled) record authentication events, index access, and document-level operations. Publish to CloudWatch Logs for compliance and forensic purposes.
- **Apply least-privilege access policies.** Map each Solr role to the minimum set of OpenSearch index permissions required. Avoid `"Principal": {"AWS": "*"}` in resource-based policies. Grant write access only to ingest service accounts, not to application read paths.
- **For FIPS 140-2 requirements, use the OpenSearch FIPS-compliant distribution.** OpenSearch provides a dedicated FIPS distribution. Solr has no equivalent — if the migration target environment requires FIPS compliance, this must be planned as part of the infrastructure selection step.
- **For multi-tenant Solr deployments, use OpenSearch Security tenants and index-level permissions.** OpenSearch Dashboards supports multi-tenancy via Security tenants for UI isolation. For data isolation, use index-level permissions to restrict each tenant to their own index or index pattern.

---

## Reindexing and Cutover Strategy

- **Use aliases for zero-downtime cutover.** Build the new OpenSearch index in parallel with the live Solr instance. Once validated, atomically swap the application's read alias to point to the new index using the `_aliases` API. This is the OpenSearch equivalent of Solr's collection alias swap.
- **Utilize the SolrReader plugin for initial data ingestion.** Since the OpenSearch _reindex API is designed only for copying data between existing OpenSearch/Elasticsearch indices, it cannot pull directly from Solr. Instead, configure the SolrReader as your source to stream documents into your pipeline.
- **Set replica count to 0 and `refresh_interval` to `-1` during initial bulk loads.** This halves write amplification and eliminates refresh overhead during the load. Restore both settings to production values and trigger a manual `POST /<index>/_refresh` when the load completes.
- **Use the Bulk API for all initial data loads.** Start with 3–5 MiB per bulk request and tune upward until throughput stops improving. Always inspect `items[].*.error` in the bulk response — the API returns HTTP 200 even when individual documents fail.
- **Take a snapshot before every major migration step.** Snapshot before the initial load, before any mapping change requiring reindex, and before cutover. Register an S3 snapshot repository and use Snapshot Management (SM) policies for automated retention.
- **Validate query parity before cutover.** Run a representative set of production Solr queries against the new OpenSearch index and compare result sets, ordering, and facet counts. Score values will differ (BM25 vs. TF-IDF) but the top-N documents should be equivalent. Automate this comparison as part of the migration acceptance criteria.
- **Plan for a dual-write period.** For high-availability migrations, write to both Solr and OpenSearch simultaneously for a validation window before cutting read traffic over. This allows rollback to Solr if issues are discovered post-cutover without data loss.
- **Use `_reindex` with a `query` parameter to migrate subsets.** If only a portion of a Solr collection needs to be migrated (e.g. documents from the last 2 years), use the `query` parameter in the `_reindex` request body to filter documents at reindex time rather than post-processing.
- **Disable shard allocation during rolling restarts.** Set `cluster.routing.allocation.enable: none` before restarting a node, then re-enable it after the node rejoins. This prevents unnecessary shard movement during the restart window and is especially important during post-migration cluster tuning.

---

## Client Library Migration

- **Replace SolrJ with `opensearch-java`.** SolrJ's `SolrClient` and `QueryResponse` have no direct equivalent — the OpenSearch Java client uses a builder-based DSL for requests and typed response models. Update all query construction, response parsing, and error handling code.
- **Replace `pysolr` with `opensearch-py`.** The OpenSearch Python client follows the same connection and request patterns as the Elasticsearch Python client. Replace `solr.search(q, **kwargs)` with `client.search(index=..., body={...})`.
- **Do not use Elasticsearch clients against OpenSearch.** Elasticsearch clients may work against older OpenSearch versions but are not tested against current releases and may break on version upgrades. Always use the official OpenSearch client for the target language.
- **Update base URLs from Solr handler paths to OpenSearch API paths.** Solr queries go to `/solr/<collection>/select`; OpenSearch queries go to `/<index>/_search`. Update requests go to `/solr/<collection>/update` → `/<index>/_doc` or `/_bulk`. Admin operations move from the Solr Admin UI to OpenSearch Dashboards or the CAT/cluster APIs.
- **Handle bulk indexing errors at the item level.** Solr's `/update` returns a top-level error on failure. The OpenSearch `_bulk` API returns HTTP 200 even when individual items fail — always inspect `items[].*.error` in the response and implement per-item retry logic with exponential backoff for 429s and dead-letter handling for 400s.
- **Migrate Solr Admin UI usage to OpenSearch Dashboards.** Index management, query dev tools, cluster health, and slow log analysis are all available in OpenSearch Dashboards. The Index Management plugin provides a UI for ISM policies and index operations.
- **Configure connection pooling and retry logic.** Production clients should use a connection pool sized to the number of coordinating nodes, with exponential backoff on 429 (Too Many Requests) and 503 responses. Always set a socket timeout — a 30-second timeout is a reasonable starting point for search.
- **Use `_source` filtering to reduce response payload.** Solr's `fl` parameter selects which stored fields to return. The OpenSearch equivalent is `_source_includes` or `_source_excludes` in the search request. Migrate all `fl` usage to `_source` filtering to reduce network transfer and deserialization cost.
- **Replace SolrCloud `CloudSolrClient` ZooKeeper connection with a standard OpenSearch client.** `CloudSolrClient` connects to ZooKeeper for cluster discovery. OpenSearch has no ZooKeeper — point the client directly at the cluster load balancer or VPC endpoint. No special cloud client is needed.

---

## Post-Migration Operational Practices

- **Use Index State Management (ISM) to replace Solr collection management scripts.** ISM automates rollover, snapshot, storage tier migration, and deletion based on index age, size, or document count — replacing the cron jobs or scripts typically used to manage Solr collections.
- **Monitor JVM heap pressure.** Both Solr and OpenSearch are JVM-based, but heap sizing rules differ. Set OpenSearch heap to no more than 50% of available RAM, capped at 32 GiB (the compressed OOPs boundary). Leave the rest for the OS file system cache that Lucene relies on. JVM heap above 85% causes cluster instability — alert on `jvm.mem.heap_used_percent > 85`.
- **Enable search and indexing slow logs.** Solr's slow query log has a direct equivalent in OpenSearch. Configure thresholds (1–5 seconds for warn-level) and publish to CloudWatch Logs. Review weekly and use findings to drive query optimization.
- **Use dedicated cluster manager nodes.** Unlike Solr's ZooKeeper ensemble (which is external), OpenSearch's cluster manager is built in. Use three dedicated cluster manager nodes for production to maintain quorum and isolate cluster state management from data operations.
- **Deploy across three Availability Zones.** Multi-AZ with Standby (two active AZs + one standby) tolerates the loss of a single AZ without data loss. Configure two replica shards per index so each AZ holds a full copy.
- **Keep OpenSearch versions current.** Run upgrades on a test domain first, validate query parity, then promote to production. Ensure all client libraries are updated to compatible versions immediately after a domain upgrade.
- **Use aliases for all application index access.** Solr collection aliases are a common pattern for zero-downtime schema changes. Replicate this in OpenSearch by routing all reads and writes through aliases — never hardcode concrete index names in application code. Use the `_aliases` API to atomically swap aliases during reindexes or schema migrations.
- **Enable Auto-Tune on Amazon OpenSearch Service domains.** Auto-Tune automatically adjusts JVM settings, cache sizes, and queue sizes based on observed workload patterns. Enable it on all production domains and configure a maintenance window for changes to be applied.
- **Monitor with CloudWatch alarms.** At minimum, alert on: `ClusterStatus.red`, `FreeStorageSpace` (< 20%), `JVMMemoryPressure` (> 85%), `CPUUtilization` (> 80%), and `AutomatedSnapshotFailure`. These cover the most common post-migration operational failures.
- **Use the CAT APIs for operational visibility.** `GET /_cat/shards`, `GET /_cat/allocation`, and `GET /_cat/nodes` provide a quick view of shard distribution, storage balance, and node health — equivalent to Solr's Admin UI collection overview.
- **Buffer ingest traffic with a queue layer.** Solr deployments often accept writes directly from application services. In OpenSearch, place a queue (Amazon Kinesis, Kafka, SQS) between producers and the cluster to absorb traffic spikes, enable backpressure, and decouple write throughput from cluster capacity.
- **Use UltraWarm and cold storage for aging Solr data.** If the Solr deployment holds large volumes of historical data that is rarely queried, migrate it to OpenSearch UltraWarm (S3-backed, cached on warm nodes) or cold storage rather than hot storage. Use ISM policies to automate the transition based on index age.
- **Validate facet counts match Solr after migration.** Facet count discrepancies between Solr and OpenSearch are a common post-migration issue. They can arise from differences in shard-level aggregation (OpenSearch uses a two-phase reduce by default), analyzer differences affecting term counts, or replica inconsistency. Use `shard_size` in `terms` aggregations to improve accuracy at the cost of slightly higher memory usage.

---

## Common Solr Migration Pitfalls

These are the most frequently encountered mistakes when migrating from Solr to OpenSearch. Treat each as a mandatory check before declaring the migration complete.

- **Carrying over Solr's `_all` field assumption.** Solr's catch-all search field is typically named `text` or `_text_` and populated via `copyField`. OpenSearch removed the `_all` field. Ensure every field that should be searchable via a catch-all query has `copy_to` pointing to a dedicated search field, and that the query targets that field explicitly.
- **Using `match` queries on `keyword` fields.** After migrating `StrField` to `keyword`, developers sometimes continue using `match` queries (which run analysis) instead of `term` queries (which do not). This causes unexpected mismatches when the stored value differs in case from the query string.
- **Ignoring the BM25 vs. TF-IDF score shift.** Applications that use raw Solr scores for thresholding, ranking, or display will behave differently after migration. Audit all code paths that consume `score` from search responses and re-calibrate thresholds against BM25 output.
- **Not accounting for `_source` vs. stored fields.** Solr returns stored fields individually; OpenSearch returns the full `_source` document by default. If the Solr schema stored only a subset of fields, the OpenSearch `_source` will contain all indexed fields unless `_source` filtering or `_source: false` is configured explicitly.
- **Reusing Solr shard counts without validating shard size.** A Solr collection with 10 shards and 5 GiB of data should not become an OpenSearch index with 10 shards — each shard would be 500 MiB, far below the 10 GiB minimum target. Over-sharding wastes heap and degrades performance.
- **Forgetting to restore `refresh_interval` and replica count after bulk load.** Setting `refresh_interval: -1` and `number_of_replicas: 0` during initial load is correct, but failing to restore them leaves the index with no replicas (no redundancy) and no automatic refresh (new documents invisible to search).
- **Assuming Solr's `fl=*` behavior maps to OpenSearch's default response.** Solr's `fl=*` returns all stored fields. OpenSearch returns the full `_source` by default, which may include fields that were not stored in Solr. Use `_source_includes` to explicitly select the fields the application needs.
- **Not testing synonym behavior after migration.** Synonym expansion in Solr and OpenSearch can differ subtly depending on whether synonyms are applied at index time or search time, and whether the `synonym` or `synonym_graph` filter is used. Always test synonym queries against a representative query set before cutover.
- **Treating yellow cluster status as acceptable.** A yellow cluster has unassigned replica shards — it has no redundancy for those shards. Do not accept yellow as steady state post-migration. Investigate and resolve the cause (insufficient nodes, disk pressure, shard limit exceeded) before declaring the migration successful.
- **Using Elasticsearch client libraries.** Elasticsearch and OpenSearch diverged after OpenSearch 1.0. Elasticsearch clients may appear to work but can fail silently on version-specific API differences. Replace all Elasticsearch client dependencies with their official OpenSearch equivalents before going to production.

---

## Migration Readiness Checklist

Use this checklist to verify OpenSearch is correctly configured before cutting over from Solr:

| Area | Check |
|---|---|
| Schema | Explicit mappings defined; `dynamic: strict`; all Solr field types translated |
| copyField | All `<copyField>` directives replaced with `copy_to` on source fields |
| Analyzers | All custom analyzers verified with `_analyze` API; index/search analyzers matched |
| Synonyms | Synonym filters applied at search time only; synonym files loaded and tested |
| Queries | Representative query set translated and validated for result parity |
| Facets | All Solr facets replaced with `terms`/`range`/`date_histogram` aggregations on `keyword` fields |
| Boosting | `bq`/`bf` boosts replaced with `function_score`; `pf` phrase boosts approximated |
| Pagination | `cursorMark` replaced with `search_after`; `max_result_window` not raised |
| Clients | SolrJ/pysolr replaced with official OpenSearch clients; no Elasticsearch-only clients |
| Security | Solr auth model replaced with OpenSearch Security FGAC; roles and mappings defined |
| Ingest | `UpdateRequestProcessorChain` replaced with ingest pipelines; DIH replaced with external tool |
| Sharding | Shard count validated against 10–50 GiB target; count is multiple of data node count |
| Replicas | At least one replica per index; two for Multi-AZ deployments |
| Aliases | All application reads and writes go through aliases, not concrete index names |
| Snapshots | S3 snapshot repository registered; snapshot taken before cutover |
| Slow logs | Search and indexing slow logs enabled with thresholds configured |
| k-NN | All k-NN indexes use `faiss` engine; `nmslib` not used |
| Dual-write | Dual-write period planned; rollback procedure to Solr documented and tested |

---

## Key Behavioral Differences to Communicate to Stakeholders

These differences between Solr and OpenSearch affect end-user search behavior and must be communicated to product and business stakeholders before cutover:

| Behavior | Solr | OpenSearch | Impact |
|---|---|---|---|
| Default relevance algorithm | TF-IDF (classic) | BM25 | Result ordering will change; re-tune score thresholds |
| Facet counts | Exact (single-shard) | Approximate by default (multi-shard) | Counts may differ slightly; use `shard_size` to improve accuracy |
| Deep pagination | `cursorMark` | `search_after` | Client code must change; semantics are similar |
| Field collapsing | `{!collapse}` | `collapse` parameter | Syntax change; behavior equivalent |
| Spatial queries | `{!geofilt}`, `{!bbox}` | `geo_distance`, `geo_bounding_box` | Parameter names differ; behavior equivalent |
| Autocomplete | `EdgeNGramFilterFactory` | `search_as_you_type` or `edge_ngram` | Requires re-indexing; behavior equivalent |
| Spell correction | `SpellCheckComponent` | `term`/`phrase` suggester | API shape differs; behavior equivalent |
| Result grouping | `group.field` | `collapse` + `top_hits` aggregation | API shape differs; behavior equivalent |
| Highlighting | `hl.*` parameters | `highlight` in request body | Parameter names differ; behavior equivalent |
| Date math | `NOW+1DAY/DAY` | `now+1d/d` | Lowercase units; update all date range queries |
| Atomic field updates | `set`, `add`, `inc` modifiers | `_update` API with `doc` or `script` | API shape differs; behavior equivalent |
| Admin UI | Solr Admin UI | OpenSearch Dashboards | Different tool; equivalent capabilities |

> **Note for the migration advisor:** When presenting these differences to a Product Manager or Business Stakeholder, translate each row into a user-facing impact statement (e.g. "search result ordering may change slightly — we will validate this before go-live"). For a Search Engineer, present the full technical detail and provide before/after query examples for each behavioral difference.
---

## Solr-to-OpenSearch Concept Mapping Reference

| Solr Concept | OpenSearch Equivalent | Notes |
|---|---|---|
| Collection | Index | One collection → one index with its own mapping |
| `schema.xml` / managed-schema | Index mapping | Define via PUT mapping or index template |
| `solrconfig.xml` | Index settings + ingest pipelines | No single equivalent file; split across settings and pipelines |
| `StrField` | `keyword` | Exact match, no analysis |
| `TextField` | `text` | Analyzed; specify analyzer in mapping |
| `IntPointField` / `TrieIntField` | `integer` | Trie types are deprecated; use Point equivalents |
| `LongPointField` | `long` | |
| `FloatPointField` | `float` | |
| `DoublePointField` | `double` | |
| `DatePointField` | `date` | Specify format string explicitly |
| `BoolField` | `boolean` | |
| `BinaryField` | `binary` | |
| `LatLonPointSpatialField` | `geo_point` | |
| `SpatialRecursivePrefixTreeFieldType` | `geo_shape` | |
| `<copyField>` | `copy_to` on source field | Destination field needs no `store` |
| `dynamicField` | `dynamic_templates` | Use with `dynamic: strict` on root |
| `docValues="true"` | `doc_values: true` (default) | Enabled by default in OpenSearch |
| `stored="true"` | `_source` | OpenSearch stores the full source document |
| `indexed="false"` | `index: false` on field | Field stored in `_source` but not searchable |
| `multiValued="true"` | Array field (automatic) | OpenSearch fields are multi-valued by default |
| `<analyzer type="index">` | `analyzer` on field mapping | |
| `<analyzer type="query">` | `search_analyzer` on field mapping | |
| `synonyms.txt` | `synonym_graph` token filter | Apply at search time only |
| `fq` (filter query) | `bool.filter` clause | Cached; does not affect score |
| `q` (main query) | `bool.must` or `bool.should` | Affects relevance score |
| `fl` (field list) | `_source_includes` / `_source_excludes` | |
| `rows` | `size` | |
| `start` | `from` (avoid for deep pagination) | Use `search_after` instead |
| `cursorMark` | `search_after` | Requires stable sort with tiebreaker |
| `facet.field` | `terms` aggregation on `keyword` field | |
| `facet.range` | `range` or `date_histogram` aggregation | |
| Pivot facets | Nested `terms` aggregations | Result shape differs |
| `{!collapse}` | `collapse` parameter | Syntax differs; semantics equivalent |
| `{!join}` | `nested` query or application-side join | No cross-index join in OpenSearch |
| `{!parent}` / `{!child}` | `nested` query with `inner_hits` | |
| `bq` / `bf` boost | `function_score` with `boost_mode: sum` | Additive vs. multiplicative semantics differ |
| `pf` / `pf2` / `pf3` phrase boost | `multi_match` type `phrase` in `bool.should` | Approximation only |
| `{!geofilt}` | `geo_distance` query | Parameter names differ |
| `{!bbox}` | `geo_bounding_box` query | Parameter names differ |
| `MoreLikeThis` handler | `more_like_this` query | Parameter names differ |
| `SpellCheckComponent` | `term` or `phrase` suggester | |
| Autocomplete / `EdgeNGramFilterFactory` | `search_as_you_type` field type or `edge_ngram` filter | |
| `QueryElevationComponent` | `pinned` query | |
| `UpdateRequestProcessorChain` | Ingest pipeline | |
| `DIH` (Data Import Handler) | Data Prepper, Logstash, or custom app | No built-in equivalent |
| Streaming Expressions | OpenSearch aggregations or application layer | No equivalent |
| `{!graph}` graph traversal | No equivalent | Requires application redesign |
| SolrCloud + ZooKeeper | OpenSearch cluster + dedicated cluster manager nodes | No ZooKeeper dependency |
| Solr collection alias | OpenSearch index alias | Atomic swap via `_aliases` API |
| Solr Basic Auth | OpenSearch Security FGAC | More granular; index/doc/field level |
| Solr Kerberos | OpenSearch Security Kerberos domain | |
| Solr PKI | OpenSearch TLS client certificates | |
| Rule-Based Authorization Plugin | OpenSearch Security roles and role mappings | |
| SolrJ | `opensearch-java` | |
| pysolr | `opensearch-py` | |
| rsolr / solr-ruby | `opensearch-ruby` | |
| Solr Admin UI | OpenSearch Dashboards | |
| `/solr/<collection>/select` | `/<index>/_search` | |
| `/solr/<collection>/update` | `/<index>/_doc` or `/_bulk` | |
| Solr slow query log | OpenSearch search slow log | Configure thresholds; publish to CloudWatch |
| Solr collection management scripts | Index State Management (ISM) policies | |
