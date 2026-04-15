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

- `solr.UUIDField` → `keyword` (store as exact identifier)
- `solr.EnumField` → `keyword` (enumerations as exact values)
- `solr.BBoxField` → `geo_shape` (rectangle as shape; use ENVELOPE or polygon)

Note: `solr.PointType` (n-dimensional point, not lat/lon) has no direct built-in equivalent. Options:
- If it actually represents geo points, migrate to `geo_point`.
- Otherwise, consider modeling as an `object` with numeric fields or separate numeric fields.

**Note:** Legacy "Trie" types (e.g., `TrieIntField`) from Solr 4/5 should be mapped to the same OpenSearch numeric types as the newer "Point" types (e.g., `IntPointField`).

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

Tip for older Solr schemas: suffix wildcards like `*_s`, `*_i`, etc., are very common. If your Solr dynamic fields also use prefix wildcards (e.g., `attr_*`), use a matching wildcard in the OpenSearch `match` as shown in the example in section 6.

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

- **Analysis and Tokenization**: Solr defines analyzers, tokenizers, and filters within `fieldType` in `schema.xml`. In OpenSearch, these are defined in the `settings` under `analysis`. You must migrate these custom analyzers separately and reference them by name in the mapping using the `analyzer` parameter.
- **Internal Fields**: Fields like `_version_` and `_root_` are internal to Solr and should generally be omitted from OpenSearch mappings.
- **Schema-less Mode**: While OpenSearch supports dynamic mapping (schema-less), it is recommended to use explicit mappings for production to avoid type conflicts (e.g., a field being detected as `long` instead of `date`).

## 6. Example Migration

**Solr Schema Snippet:**
```xml
<schema name="example" version="1.6">
  <uniqueKey>id</uniqueKey>
  <fieldType name="string" class="solr.StrField"/>
  <fieldType name="text_en" class="solr.TextField"/>
  
  <field name="id" type="string" indexed="true" stored="true" required="true"/>
  <field name="title" type="text_en" indexed="true" stored="true"/>
  <field name="category" type="string" indexed="true" stored="true" multiValued="true"/>
  
  <dynamicField name="attr_*" type="string" indexed="true" stored="true"/>
  
  <copyField source="title" dest="text_all"/>
  <field name="text_all" type="text_en" indexed="true" stored="false"/>
</schema>
```

**OpenSearch Index Mapping:**
```json
{
  "mappings": {
    "dynamic_templates": [
      {
        "dynamic_attr": {
          "match": "attr_*",
          "match_pattern": "wildcard",
          "mapping": {
            "type": "keyword"
          }
        }
      }
    ],
    "properties": {
      "id": { "type": "keyword" },
      "title": { 
        "type": "text",
        "copy_to": "text_all"
      },
      "category": { "type": "keyword" },
      "text_all": { "type": "text" }
    }
  }
}
```

## 7. Solr 6 and Legacy Considerations

The Solr 6 era transitioned from legacy "Trie" numeric fields to "Point" numeric fields and changed several defaults:

- **Numeric fields**: Prefer `IntPointField`, `LongPointField`, etc. over `Trie*` classes. Map both to the same OpenSearch numeric types (section 1).
- **DocValues defaults**: In older schemas (`version < 1.7`), DocValues were not enabled by default per-field. Explicitly set `doc_values: true` on OpenSearch fields used for sorting or aggregations.
- **Managed schema**: Solr 6 defaults to `managed-schema`. Export via the Solr Schema API (`/solr/<collection>/schema`) to get a stable JSON view for migration.
- **Spatial fields**: `LatLonPointSpatialField` → `geo_point`; `SpatialRecursivePrefixTreeFieldType` (RPT) → `geo_shape`; `BBoxField` → `geo_shape` (ENVELOPE).
- **Identifiers/enums**: `UUIDField` and `EnumField` → `keyword`.
- **Multi-valued fields**: Solr requires explicit `multiValued="true"`; OpenSearch supports arrays on all fields by default.
- **Required/Unique**: Solr `required="true"` and `uniqueKey` constraints are not enforced by OpenSearch mappings. Enforce at ingest/application level. Map `uniqueKey` to document `_id`.

## 8. Analyzer Migration

- Solr analyzer chains are defined under each `fieldType` (`<analyzer>`, `<tokenizer>`, `<filter>`). In OpenSearch, define analyzers under index `settings.analysis` and reference them from field mappings using `analyzer`, `search_analyzer`, or `normalizer` (for `keyword` fields).
- Common Solr 6 filters have OpenSearch equivalents (e.g., `LowerCaseFilter`, `StopFilter`, `SynonymGraphFilter`). See `03-analysis-pipelines.md` for full tokenizer and filter mapping tables.
- If a Solr filter has no direct equivalent, consider a pre-ingest transform via Ingest Pipelines or use the nearest equivalent filter.
