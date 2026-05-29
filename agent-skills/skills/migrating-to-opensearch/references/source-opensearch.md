# Source: OpenSearch (upgrade)

You MUST read this when the source is OpenSearch and the user is upgrading versions or moving between deployment shapes (self-managed → AOS, AOS → Serverless NextGen, cross-region, etc.).

## Step 1 — Retrieve current AWS guidance

Authoritative upgrade matrix, EOL schedule, and snapshot-based migration tutorial live in the AWS developer guide. You MUST retrieve every assessment per the recipe in [`knowledge-retrieval.md`](knowledge-retrieval.md) (Amazon OpenSearch Service (managed) section). For self-managed upgrades, the OpenSearch Project upgrade-or-migrate / upgrade-paths pages are catalogued in [`knowledge-retrieval.md`](knowledge-retrieval.md) (OpenSearch Project (engine docs) section).

## Step 2 — Critical invariants (you MUST retrieve to confirm currency)

These rarely change. You MUST verify against the live doc every assessment:

- **OS 1.3 / 2.x → OS 3.x is a two-step upgrade** — you MUST reach OS 2.19 first.
- **Indexes from OS 1.3 / ES 7.10 or earlier are NOT readable in OS 3.x** (Lucene 10 incompatibility). You MUST reindex first.
- **Snapshots are forward-compatible by exactly one major version**.
- **You MUST upgrade cluster manager nodes LAST** because older managers accept newer nodes but newer managers do not accept older nodes.
- **You MUST NOT downgrade nodes** because OpenSearch does not support node downgrade and a downgrade attempt will corrupt cluster state.

Source: the AWS upgrade-path doc (Amazon OpenSearch Service (managed) section) and the rolling-upgrade page on the OpenSearch project docs (OpenSearch Project (engine docs) section) — both catalogued in [`knowledge-retrieval.md`](knowledge-retrieval.md).

## Step 3 — Breaking changes (you MUST retrieve)

The breaking-changes index lives in the documentation-website repo and is updated per minor release. Retrieve the raw markdown / browseable page and the 3.0 announcement per [`knowledge-retrieval.md`](knowledge-retrieval.md) (OpenSearch Project (engine docs) section).

Key OS 2.x → 3.x items you MUST always flag (you MUST verify currency by retrieval):

- **JDK 21 minimum** (per breaking-changes.md 3.0.0; OS 3.x bundled JDK is 21+)
- **k-NN engine: NMSLIB deprecated** (3.0; not yet removed). FAISS (default since 2.18) or Lucene engine recommended.
- **Removed k-NN index settings**: `index.knn.algo_param.{ef_construction, m}`, `index.knn.space_type`, `knn.plugin.enabled` (move to mapping `parameters`)
- **Java Security Manager → Java agent** (driven by JDK 24's permanent removal of the Security Manager)
- **Bulk API: 512-byte `_id` limit** now consistently enforced across all APIs (previously the Bulk API allowed longer IDs)
- **System indexes: REST API access removed** (deprecated since 1.x)
- **Searchable snapshots: `warm` role** (was `search`) — nodes serving searchable-snapshot shards must use the `warm` role
- **WLM rename: `wlm/query_group` endpoint → `wlm/workload_group`**; response field `queryGroupID` → `workloadGroupID`
- **SQL plugin: DSL response format removed; `DELETE` statement removed; pagination defaults to Point-in-Time (PIT)** — Scroll API deprecated
- **Performance Analyzer (RCA) agent removed → Telemetry plugin** (OpenTelemetry-based)
- **Romanian analyzer change** (cedilla→comma; reindex Romanian docs)
- **`compatibility.override_main_response_version` setting REMOVED in 3.0** — legacy ES OSS clients that probe via `GET /` will not see the 7.10 response shape on a 3.x target

> **Skill IP**: claims about a `LegacyBM25Similarity` → `BM25Similarity` default change are not present in the current OpenSearch breaking-changes file. You MUST verify any BM25-default-change claim against the live breaking-changes file via [`knowledge-retrieval.md`](knowledge-retrieval.md) (OpenSearch Project (engine docs) section) before quoting it to a customer.

Key OS 1.x → 2.x items:
- Mapping `type` parameter removed everywhere (OS 2.0)
- JDK 8 dropped (OS 2.0; Lucene upgrade forced this)
- Inclusive-terminology renames (legacy → current): allow list, deny list, cluster manager (deprecated in 2.x; permanently removed in 3.0)
- **2.18: default k-NN engine changed from NMSLIB to FAISS** (k-NN release notes 2.18.0.0 — "Update default engine to FAISS")
- **2.19: NMSLIB engine deprecated** (k-NN release notes 2.19.0.0 — "Deprecate nmslib engine"); FAISS or Lucene engine recommended

## Step 4 — Discovery checklist

The standard intake (cluster topology, version, plugin list, index counts/sizes, ISM, role mappings) lives in [`intake.md`](intake.md) and [`source-elasticsearch.md`](source-elasticsearch.md) — both apply to OS sources. You MUST normalize the inputs into the fingerprint JSON shape documented in [`SKILL.md`](../SKILL.md) Step 2 with `engine: "opensearch"`.

## Step 5 — Self-managed → AOS / Serverless NextGen

For self-managed → **AOS (managed)**: you MUST walk network (VPC vs public; PrivateLink), auth (SAML, IAM/SigV4, Cognito, FGAC), encryption (KMS at rest, TLS in transit), and plugins (only the supported list — retrieve via [`knowledge-retrieval.md`](knowledge-retrieval.md) (Amazon OpenSearch Service (managed) section)).

For self-managed → **Serverless NextGen**: no direct migration path. You MUST reindex from snapshot or via OSI (use the `AWS-OpenSearchDataMigrationPipeline` blueprint). Retrieve the serverless comparison and general reference docs via [`knowledge-retrieval.md`](knowledge-retrieval.md) (Amazon OpenSearch Serverless NextGen section) for current limits. See [`decision-trees.md`](decision-trees.md) for the six-family selection matrix.

## Step 6 — Cross-cluster replication (CCR) for migration

Use case: bootstrap a target cluster while source still serves traffic; flip clients when latency converges. You MUST retrieve current limits via [`knowledge-retrieval.md`](knowledge-retrieval.md) (Amazon OpenSearch Service (managed) section — cross-cluster replication).

Standing rules (you MUST verify the live numbers against the AWS replication doc — see [`knowledge-retrieval.md`](knowledge-retrieval.md), Amazon OpenSearch Service (managed) section):

- Active-passive (follower pulls from leader)
- Minimum versions and FGAC + node-to-node encryption requirements — verify against the live `replication.html` doc per [`knowledge-retrieval.md`](knowledge-retrieval.md).
- `index.soft_deletes.enabled=true` on leader (default since ES 7.0+; pre-7.0 indexes MUST be reindexed)
- You MUST NOT use CCR between Serverless NextGen and self-managed because the Serverless NextGen service does not expose the CCR APIs. You MUST NOT replicate from follower→follower because the follower index is read-only and not a valid leader.
- **Skill IP**: connection-count limits and pause-resume windows are quota-style numbers that drift. Retrieve the live values from the AWS replication doc per [`knowledge-retrieval.md`](knowledge-retrieval.md) (Amazon OpenSearch Service (managed) section) before quoting.
- Upgrade order: **follower first**, then leader (verify against the live replication doc)

## Step 7 — Cite

Every OS upgrade report MUST include (URLs catalogued in [`knowledge-retrieval.md`](knowledge-retrieval.md)):
- The AWS upgrade-path page — Amazon OpenSearch Service (managed) section
- The specific breaking-changes file for the source/target major-version step — OpenSearch Project (engine docs) section
- For 3.x targets: the opensearch.org 3.0 announcement (cites Lucene 10, JDK 21, removed settings) — OpenSearch Project (engine docs) section
- For Serverless NextGen targets: the serverless comparison doc — Amazon OpenSearch Serverless NextGen section

## Read next

- [`decision-trees.md`](decision-trees.md) — six-family migration matrix (in-place upgrade is the OS → OS default)
- [`nuggets.md`](nuggets.md) — production gotchas (2.19 stepping stone, k-NN engine migration, Lucene 9 vs 10)
- [`knowledge-retrieval.md`](knowledge-retrieval.md)
