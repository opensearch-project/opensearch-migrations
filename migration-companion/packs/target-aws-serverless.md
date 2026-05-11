# Target pack: Amazon OpenSearch Serverless (AOSS)

AWS runs everything. There is no cluster, no domain, no shards visible
to the user — only **collections** (logical groups of indices) and
**data access policies**. AOSS is the most-restricted target in this
skill.

## Capability profile (READ THIS BEFORE PLANNING)

| Capability         | Supported | Notes                                      |
| ------------------ | --------- | ------------------------------------------ |
| **snapshot_restore** | **NO**  | Hard invariant. There is no snapshot API.  |
| snapshot_repo_s3   | no        | Not exposed.                               |
| snapshot_repo_fs   | no        | Not exposed.                               |
| reindex_from_remote| yes       | RFS is THE migration path.                 |
| painless_scripts   | partial   | Inline only, limited script context.       |
| custom_analyzers   | partial   | Built-in language analyzers only; no plugins. |
| security_plugin    | no        | Replaced by data access policies (IAM-based). |
| ism                | no        | Not available.                             |
| alerting           | no        | Not available.                             |
| knn                | yes       | Vector search collection type.             |
| ml_commons         | no        | Not available.                             |
| _all field         | no        | Removed; AOSS rejects mappings that include it. |
| index renaming     | depends   | Indices live within a collection scope.    |

## Collection types

AOSS forces you to pick a collection type at creation. They are NOT
interchangeable:

- **TIME_SERIES** — write-heavy, low query concurrency, optimized for
  logs. Good fit for ES `logs-*` patterns.
- **SEARCH** — typical search workload, standard balance.
- **VECTORSEARCH** — k-NN/embeddings.

Pick before Phase 4. Wrong choice means re-creating the collection
and re-running RFS.

## Migration path (always RFS)

Because there is no snapshot API:

```
docker run --rm \
  -e SOURCE_HOST=https://source:9200 \
  -e SOURCE_USER=... -e SOURCE_PASSWORD=... \
  -e TARGET_HOST=https://<collection-id>.<region>.aoss.amazonaws.com \
  -e TARGET_AUTH_TYPE=sigv4 \
  -e AWS_REGION=<region> \
  opensearchproject/opensearch-migrations-rfs:latest \
  --index-allowlist '...'
```

The RFS image must be on a host that has AWS credentials (env vars,
instance role, or container task role). SigV4-sign every request to
the AOSS endpoint.

## Data access policy

Before RFS can write, the IAM principal running RFS needs:

- A **data access policy** on the collection allowing
  `aoss:CreateIndex`, `aoss:WriteDocument`, `aoss:UpdateIndex`.
- A **network access policy** allowing the source-of-traffic
  (VPC, public, etc.).
- An **encryption policy** (KMS or AWS-owned).

All three are required. Missing any one → 403 with no useful detail.

## Mapping pitfalls

Mappings carry over via RFS but AOSS rejects:

- `_all` — strip from any pre-7.x source mapping.
- `index.codec: best_compression` if using TIME_SERIES (it's already
  optimized; the explicit setting can fail).
- Custom analyzers referencing non-bundled tokenizers/filters.

Validate the mapping by sending it as a `PUT /<index>` to the
collection BEFORE RFS runs the bulk load. Cheap to do, expensive to
discover at doc 5,000,000.

## Counts

AOSS caps `hits.total.value` at 10,000 by default. Always pass
`track_total_hits: true` in Phase 5 parity queries or counts will
look wrong.
