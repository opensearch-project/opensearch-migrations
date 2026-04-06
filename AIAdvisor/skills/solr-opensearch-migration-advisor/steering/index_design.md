# Solr to OpenSearch Index Design Steering

## Schema Mapping
- Solr `uniqueKey` → OpenSearch `_id`.
- Solr `multiValued="true"` → OpenSearch arrays are native, no special mapping needed.
- Solr `stored="true"` → OpenSearch `store: true` (rarely used, defaults to `_source`).
- Solr `indexed="true"` → OpenSearch `index: true` (default).
- Solr `docValues="true"` → OpenSearch `doc_values: true` (default for most types).

## Field Types
- `solr.TextField` → `text`.
- `solr.StrField` → `keyword`.
- `solr.IntField`, `solr.LongField` → `integer`, `long`.
- `solr.FloatField`, `solr.DoubleField` → `float`, `double`.
- `solr.DateField` → `date`.
- `solr.BoolField` → `boolean`.

## Analysis
- Solr Tokenizers/Filters → OpenSearch Analysers/Tokenizers/Filters.
- `copyField` → `copy_to`.
