# Solr Migration Developer Sandbox

Developer sandbox for the Solr-to-OpenSearch migration tooling. Spins up Solr + OpenSearch + Translation Shim clusters, generates and loads a synthetic dataset, and executes queries against both endpoints for end-to-end validation.

## Quick Start

```bash
./run.sh
```

One command runs the full pipeline:
1. Generate 200K synthetic product review documents
2. Start Solr 9.x + OpenSearch 3.3 + Translation Shim (Docker Compose)
3. Create schemas and bulk load data into both clusters
4. Keep clusters running for manual testing or external query execution

Add `--run-queries` to also execute 160 queries against Solr-direct and the Translation Shim.

## Flags

```bash
./run.sh --run-queries                              # Execute queries, then tear down
./run.sh --run-queries --no-teardown                # Execute queries, keep clusters running
./run.sh --run-queries --dual-mode                  # Also run dual-target validation (OpenSearch primary)
./run.sh --run-queries --dual-mode --dual-primary solr  # Dual-target with Solr primary
./run.sh --help                                     # Full usage documentation
```

## Prerequisites

- Docker & Docker Compose
- Python 3.11+ with `requests` (`pip install requests`)
- Java 17 (Amazon Corretto) — only if shim image needs building

## Endpoints

| Endpoint | Port | Description |
|---|---|---|
| Solr | http://localhost:18983 | Solr 9.x direct |
| OpenSearch | http://localhost:19200 | OpenSearch 3.3 direct |
| Shim | http://localhost:18080 | Single-target shim (OpenSearch only) |
| Dual Shim (OS primary) | http://localhost:18084 | Dual-target, returns OpenSearch response |
| Dual Shim (Solr primary) | http://localhost:18083 | Dual-target, returns Solr response |

## Directory Structure

```
solrMigrationDevSandbox/
├── run.sh                              # One-command entry point
├── docker-compose.yml                  # Solr + OpenSearch + Shim (single + dual)
├── config/
│   ├── solr/schema.xml                 # Solr field definitions
│   ├── solr/solrconfig.xml             # Solr config
│   └── opensearch/index-mapping.json   # OpenSearch index mapping
├── data/
│   ├── generate_dataset.py             # Synthetic dataset generator
│   └── README.md                       # Field documentation
├── scripts/
│   ├── setup-clusters.sh               # Health checks, schema creation, triggers data load
│   └── load_data.py                    # Bulk loads dataset into Solr + OpenSearch
├── queries/
│   └── queries.json                    # Pre-generated query set (160 queries, 54 categories)
└── src/
    ├── query_runner.py                 # Query execution (single, dual, cursor walks)
    └── run_queries.py                  # Entry point
```

## Dataset

200K synthetic product reviews with natural-language text, covering:
- Text fields (product_title, review_body, review_headline)
- Keyword fields (product_category, marketplace, product_id)
- Numeric fields (star_rating, helpful_votes, total_votes)
- Date field (review_date)
- Boolean fields (verified_purchase, vine)

## Query Categories

160 queries across 54 categories: term, phrase, wildcard, range, boolean, filter (single and multi), sort, field list, rows variations, offset and deep pagination, cursor pagination (single-request and sequential walks), highlighting (basic and advanced), facets (terms, range, datetime range, multi, pivot, with filter, legacy), boosting, default field, dismax, function queries, fuzzy, proximity, date math, fq range, stats, grouping, collapse, multi-value, local params, exists, negative, spellcheck, MLT, streaming, export, realtime get, admin, update, real-world patterns, combined multi-feature, and edge cases.

### Sequential Cursor Walks

Queries with a `"sequence"` field run multi-page cursor pagination independently against each endpoint. The runner extracts `nextCursorMark` from each response and substitutes it into the next request, walking pages until the end signal (same cursor returned) or `maxPages` is reached. This validates that both Solr-direct and the shim produce consistent paginated results using their native mechanisms.

## Dual-Target Mode

The sandbox includes two dual-target shim instances that send every query to both Solr and OpenSearch simultaneously, validate responses match, and return the primary target's response.

- `--dual-primary opensearch` (default, port 18084): Returns the OpenSearch (translated) response. Useful for validating the shim produces correct results.
- `--dual-primary solr` (port 18083): Returns the Solr (native) response. Useful for validating that OpenSearch produces equivalent results in the background.

Both dual shims run `field-equality` and `doc-count` validators that compare Solr and OpenSearch responses on every request.
