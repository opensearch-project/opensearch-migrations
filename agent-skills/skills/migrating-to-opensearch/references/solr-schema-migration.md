# Solr to OpenSearch Schema Migration

This document provides guidance on converting Apache Solr schema definitions (`schema.xml` or Schema API JSON) to OpenSearch index mappings.

## 1. Field Type Mapping

Solr field types are defined by their `class` attribute. Below is the mapping from common Solr field type classes to OpenSearch types:

| Solr Field Type Class | OpenSearch Type | Category |
| :--- | :--- | :--- |
| `solr.TextField` | `text` | Full-text search |
| `solr.StrField` | `keyword` | Exact match / Aggregations |
| `solr.BoolField` | `boolean` | Logical values |
| `solr.IntPointField`, `solr.TrieIntField` | `integer` | 32-bit integer |
| `solr.LongPointField`, `solr.TrieLongField` | `long` | 64-bit integer |
| `solr.FloatPointField`, `solr.TrieFloatField` | `float` | Single-precision float |
| `solr.DoublePointField`, `solr.TrieDoubleField` | `double` | Double-precision float |
| `solr.DatePointField`, `solr.TrieDateField` | `date` | Date/time |
| `solr.BinaryField` | `binary` | Base64 encoded binary |
| `solr.LatLonPointSpatialField` | `geo_point` | Latitude/Longitude |
| `solr.SpatialRecursivePrefixTreeFieldType` | `geo_shape` | Complex shapes (Polygons, etc.) |

Additional Solr 6-era types commonly seen and suggested mappings:

- `solr.UUIDField` → `keyword` (store as exact identifier; per [Solr field types](https://solr.apache.org/guide/solr/latest/indexing-guide/field-types-included-with-solr.html), UUIDField stores a UUID — preserve as opaque string in OpenSearch)
- `solr.EnumFieldType` (and the deprecated `solr.EnumField`) → `keyword` (enumerations as exact values; OpenSearch has no native preserved-order enum type)
- `solr.BBoxField` → `geo_shape` (rectangle as shape; use ENVELOPE or polygon)
- `solr.CurrencyFieldType` (and the deprecated `solr.CurrencyField`) → no native OpenSearch equivalent; you MUST denormalize at index time (e.g., split into `<field>_amount` numeric and `<field>_currency` keyword, plus a converted-to-base-currency numeric field for range queries). Exchange-rate management moves to the application or ingest layer.

Note: `solr.PointType` (n-dimensional point, not lat/lon) has no direct built-in equivalent. Options:
- If it actually represents geo points, you MUST migrate to `geo_point`.
- Otherwise, you SHOULD model as an `object` with numeric fields or separate numeric fields.

**Trie field types — value-level transformation required.** Solr Trie field types (`TrieIntField`, `TrieLongField`, `TrieFloatField`, `TrieDoubleField`, `TrieDateField`) were deprecated in Solr 7+ in favor of `*PointField` (per the [Solr 7 upgrade notes](https://solr.apache.org/guide/solr/latest/upgrade-notes/major-changes-in-solr-7.html)). Both legacy Trie and modern Point types map to native OpenSearch numeric / date types (`integer`/`long`/`float`/`double`/`date`). The Trie wire format is not directly portable; values MUST be re-cast to native JSON numbers during the migration. You MUST flag this as a value-transformation step in the gap register and document it per [solr-transformation-rules](solr-transformation-rules.md).

**TF-IDF → BM25 similarity change.** Solr ≤ 5.x defaulted to ClassicSimilarity (TF-IDF); Solr 6.0+ defaulted to BM25 via `SchemaSimilarityFactory` (per the [Solr 6.6 schema-elements docs](https://solr.apache.org/guide/6_6/other-schema-elements.html): "by default Solr uses an implicit `SchemaSimilarityFactory` … implicitly uses `BM25Similarity` for any field type which does not have an explicit Similarity"). OpenSearch defaults to BM25. **This is a behavioral incompatibility for any Solr ≤ 5 source, and a relevance-tuning concern for Solr 6.x+ schemas that explicitly set `ClassicSimilarityFactory`.** You MUST flag it whenever the source schema specifies (or inherits) ClassicSimilarity. The fix: either re-tune queries for BM25 scoring, or set `"similarity": { "type": "classic" }` in the OpenSearch index settings to preserve TF-IDF parity during transition. You MUST NOT tell a customer the change is dismissible because relevance regressions are the most common post-migration complaint.

## 2. Field Attribute Mapping

Solr field attributes determine how data is indexed and stored. OpenSearch handles these via mapping parameters:

| Solr Attribute | OpenSearch Parameter | Logic |
| :--- | :--- | :--- |
| `indexed="true"` | `index: true` (default) | If `false`, the field is stored but not searchable. |
| `stored="true"` | `store: true` | If `true`, the individual field value is stored separately from `_source`. |
| `docValues="true"`| `doc_values: true` | Enables disk-based data structures for sorting and aggregations. |
| `multiValued="true"`| N/A | In OpenSearch, **every** field can be multi-valued by default. |
| `required="true"` | N/A | OpenSearch does not enforce field presence at the mapping level. |
| `uniqueKey` | `_id` | The Solr `uniqueKey` field typically maps to the OpenSearch document `_id`. |

## 3. Dynamic Fields to Dynamic Templates

Solr's `dynamicField` allows for flexible schemas using wildcard patterns. These map to OpenSearch `dynamic_templates`.

**Solr Dynamic Field:**
```xml
<dynamicField name="*_t" type="text_general" indexed="true" stored="true"/>
```

**OpenSearch Dynamic Template:**
```json
{
  "dynamic_t": {
    "match": "*_t",
    "match_pattern": "wildcard",
    "mapping": {
      "type": "text"
    }
  }
}
```

Tip for older Solr schemas: suffix wildcards like `*_s`, `*_i`, etc., are very common. If your Solr dynamic fields also use prefix wildcards (e.g., `attr_*`), you MUST use a matching wildcard in the OpenSearch `match` as shown in the example in section 6.

## 4. Copy Fields (`copyField`)

Solr uses `copyField` to duplicate data from one field to another (e.g., for multiple analysis strategies). In OpenSearch, this is handled using the `copy_to` parameter in the source field's mapping.

**Solr `copyField`:**
```xml
<copyField source="title" dest="title_search"/>
```

**OpenSearch `copy_to`:**
```json
{
  "properties": {
    "title": {
      "type": "text",
      "copy_to": "title_search"
    },
    "title_search": {
      "type": "text"
    }
  }
}
```

## 5. Key Differences and Considerations

- **Analysis and Tokenization**: Solr defines analyzers, tokenizers, and filters within `fieldType` in `schema.xml`. In OpenSearch, these are defined in the `settings` under `analysis`. You MUST migrate these custom analyzers separately and reference them by name in the mapping using the `analyzer` parameter.
- **Internal Fields**: Fields like `_version_` and `_root_` are internal to Solr. You MUST omit them from OpenSearch mappings because they have no OpenSearch equivalent and cause mapping conflicts.
- **Schema-less Mode**: OpenSearch supports dynamic mapping (schema-less). You SHOULD use explicit mappings for production because dynamic mapping causes type conflicts (e.g., a field being detected as `long` instead of `date`).

## 6. Worked example (skeleton)

A complete worked example skeleton lives in [`assets/solr-index-template-skeleton.md`](../assets/solr-index-template-skeleton.md). The transformation pattern: `<uniqueKey>` → `_id`; `<field>` → `properties.<name>`; `<dynamicField name="attr_*">` → `dynamic_templates` with `match_pattern: wildcard`; `<copyField source="a" dest="b">` → `properties.a.copy_to: b`. Field types from §1; attributes from §2.

## 7. Solr 6 / legacy schema notes

- **DocValues defaults**: schemas older than `version="1.7"` did not enable DocValues per-field. You MUST explicitly set `doc_values: true` on OpenSearch fields used for sorting / aggregations.
- **Managed schema**: Solr 6+ defaults to `managed-schema`. You MUST export via the Solr Schema API (`GET /solr/<collection>/schema`) to get a stable JSON view for migration.

## 8. Analyzer migration

Solr analyzer chains are defined under each `fieldType`; in OpenSearch, you MUST define analyzers under `settings.analysis` and reference them from field mappings via `analyzer`, `search_analyzer`, or `normalizer` (for `keyword` fields). For the full analysis migration (tokenizers, token filters, char filters, synonyms, language analyzers, filter-order rules), see [`solr-analysis.md`](solr-analysis.md). If a Solr filter has no direct equivalent, you SHOULD consider a pre-ingest transform via OpenSearch Ingest Pipelines or use the nearest equivalent filter.
