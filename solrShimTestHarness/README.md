# Solr Shim Test Harness

Test harness for the Solr-to-OpenSearch translation shim. Generates a synthetic dataset, spins up Solr + OpenSearch + Translation Shim clusters, loads data, and executes queries against both endpoints.

## Quick Start

```bash
./run.sh
```

One command runs the full pipeline:
1. Generate 200K synthetic product review documents
2. Start Solr 9.x + OpenSearch 3.3 + Translation Shim (Docker Compose)
3. Create schemas and bulk load data into both clusters
4. Keep clusters running for manual testing or external query execution

Add `--run-queries` to also execute 76 queries against Solr-direct and the Translation Shim.

## Flags

```bash
./run.sh --run-queries                # Also execute queries, then tear down clusters
./run.sh --run-queries --no-teardown  # Execute queries, keep clusters running
```

## Prerequisites

- Docker & Docker Compose
- Python 3.8+ with `requests` (`pip install requests`)
- Java 17 (Amazon Corretto) — only if shim image needs building

## Directory Structure

```
solrShimTestHarness/
├── run.sh                              # One-command entry point
├── docker-compose.yml                  # Solr + OpenSearch + Shim
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
│   └── queries.json                    # Pre-generated query set (76 queries)
└── src/harness/
    ├── query_runner.py                 # Dual-path query execution
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

76 pre-generated queries across 22 categories: term, phrase, wildcard, range, boolean, filter (single and multi), sort, field list, rows variations, offset and deep pagination, highlighting (basic and advanced), facets (terms, range, datetime range, multi, with filter), boosting, default field, and edge cases.
