# Data Transformation Rules: Solr to OpenSearch

You MUST apply these rules to every document during migration. You MUST NOT skip a rule because a field looks correct because silent transformation errors only surface after cutover. You MUST validate each transformation explicitly.

## Rules

- **Timestamps:** You MUST map Solr date fields to OpenSearch `"type": "date"`. Two equivalent formats are acceptable; you MUST pick ONE per field and stay consistent across re-indexing:
  - **ISO-8601 strings** (e.g. `2024-01-15T10:30:00Z`) — you MUST set `"format": "strict_date_optional_time"` (OpenSearch's default; matches Solr's wire format with no transformation).
  - **`epoch_millis`** (long integer) — you MUST set `"format": "epoch_millis"` and convert ISO-8601 strings to milliseconds-since-epoch at index time.
  Combining both via `"format": "strict_date_optional_time||epoch_millis"` is permitted during phased migrations where some producers still emit ISO-8601 strings while others have switched to epoch_millis.
- **Trie / numeric fields:** You MUST cast values from Solr Trie types (`TrieIntField`, `TrieLongField`, `TrieFloatField`, `TrieDoubleField`) to native JSON numbers. You MUST remove any string encoding. You MUST map to OpenSearch `integer`, `long`, `float`, or `double` accordingly.
- **String-encoded numbers:** If a field is typed as numeric in the schema but arrives as a string (e.g. `"price": "29.99"`), you MUST coerce to the correct numeric type before indexing. You MUST reject documents where coercion fails. You MUST NOT index a null because a null silently loses the value and breaks aggregations.
- **Multi-value fields:** Solr multi-valued fields arrive as arrays. You MUST preserve array structure in OpenSearch. You MUST NOT flatten a multi-value field to a single value because this causes silent data loss.
- **Booleans:** You MUST normalize to JSON `true`/`false`. You MUST reject string variants (`"yes"`, `"1"`, `"TRUE"`) and coerce them to boolean before indexing because OpenSearch strict-boolean parsing rejects string variants and silently fails ingestion otherwise.
- **Geo fields:** You MUST convert Solr `LatLonPointSpatialField` string format (`"lat,lon"`) to an OpenSearch `geo_point` object: `{"lat": <float>, "lon": <float>}`. You MUST map the field type to `"geo_point"`.
- **Field names with dots:** Solr allows dots in field names; OpenSearch interprets dots as object path separators. You MUST replace dots with underscores (e.g. `product.id` → `product_id`) and update the mapping accordingly.
- **Solr internal fields:** You MUST strip `_version_`, `_root_`, and `_nest_path_` from every document before indexing because these Solr internals have no OpenSearch equivalent and will cause mapping conflicts.
- **Document identity:** You MUST use the Solr `uniqueKey` field value as the OpenSearch `_id`. You MUST set it explicitly on every index request to preserve upsert behavior. You MUST NOT rely on auto-generated IDs because doing so breaks idempotent re-indexing and dedup-by-fingerprint workflows (unless the source collection had no `uniqueKey`).
- **Text cleanup:** You MUST strip residual HTML/XML markup from text fields unless the application intentionally stores markup. You MUST normalize whitespace (collapse runs of spaces/tabs/newlines to a single space, trim leading/trailing whitespace).

## What Counts as a Transformation Error

- A date field whose mapping `"format"` does NOT cover the actual on-the-wire encoding (e.g. ISO-8601 strings into `"format": "epoch_millis"`).
- A numeric field indexed as a string in a numeric-typed mapping.
- A multi-value field silently truncated to one value.
- A document indexed with a Solr internal field present.
- A geo field stored as a `"lat,lon"` string in a `geo_point` mapping.

You MUST flag any of the above as a **Breaking** incompatibility and surface it to the user before proceeding.
