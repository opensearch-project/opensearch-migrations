# Solr to OpenSearch Query Translation Steering

## Overview
Translate individual Solr queries (standard, dismax, edismax) into OpenSearch Query DSL.

## Key Conversions
- `q`: Map to `query` in OpenSearch.
- `qf` (Query Fields): Map to `multi_match` with `fields` and `type: "best_fields"`.
- `pf` (Phrase Fields): Map to `multi_match` with `type: "phrase"`.
- `mm` (Minimum Should Match): Map to `minimum_should_match` in `bool` or `multi_match`.
- `boost`: Map to `boost` or use `function_score`.
- `fq` (Filter Query): Map to `filter` in a `bool` query.

## Examples
- Solr: `title:opensearch AND price:[10 TO 100]`
  OpenSearch: `{"query": {"bool": {"must": [{"match": {"title": "opensearch"}}, {"range": {"price": {"gte": 10, "lte": 100}}}]}}}`
