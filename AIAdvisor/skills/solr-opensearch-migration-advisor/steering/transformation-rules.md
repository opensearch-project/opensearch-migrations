# Data Transformation Rules: Solr to OpenSearch

Apply these rules to every document during migration. Do not skip a rule because a field looks correct — validate each transformation explicitly.

## Rules

- **Timestamps:** Convert all Solr date fields (ISO-8601 strings, e.g. `2024-01-15T10:30:00Z`) to `epoch_millis` (long integer). Map the OpenSearch field type to `"date"` with `"format": "epoch_millis"`. Never store date strings in a `date` field.
- **Trie / numeric fields:** Cast values from Solr Trie types (`TrieIntField`, `TrieLongField`, `TrieFloatField`, `TrieDoubleField`) to native JSON numbers. Remove any string encoding. Map to OpenSearch `integer`, `long`, `float`, or `double` accordingly.
- **String-encoded numbers:** If a field is typed as numeric in the schema but arrives as a string (e.g. `"price": "29.99"`), coerce to the correct numeric type before indexing. Reject documents where coercion fails rather than indexing a null.
- **Multi-value fields:** Solr multi-valued fields arrive as arrays. Preserve array structure in OpenSearch. Never flatten a multi-value field to a single value — this causes silent data loss.
- **Booleans:** Normalize to JSON `true`/`false`. Reject string variants (`"yes"`, `"1"`, `"TRUE"`) — coerce them to boolean before indexing.
- **Geo fields:** Convert Solr `LatLonPointSpatialField` string format (`"lat,lon"`) to an OpenSearch `geo_point` object: `{"lat": <float>, "lon": <float>}`. Map the field type to `"geo_point"`.
- **Field names with dots:** Solr allows dots in field names; OpenSearch interprets dots as object path separators. Replace dots with underscores (e.g. `product.id` → `product_id`) and update the mapping accordingly.
- **Solr internal fields:** Strip `_version_`, `_root_`, and `_nest_path_` from every document before indexing. These fields are Solr internals with no OpenSearch equivalent and will cause mapping conflicts.
- **Document identity:** Use the Solr `uniqueKey` field value as the OpenSearch `_id`. Set it explicitly on every index request to preserve upsert behavior. Do not rely on auto-generated IDs unless the source collection had no `uniqueKey`.
- **Text cleanup:** Strip residual HTML/XML markup from text fields unless the application intentionally stores markup. Normalize whitespace (collapse runs of spaces/tabs/newlines to a single space, trim leading/trailing whitespace).

## What Counts as a Transformation Error

- A date field indexed as a string in a `date`-typed mapping.
- A numeric field indexed as a string in a numeric-typed mapping.
- A multi-value field silently truncated to one value.
- A document indexed with a Solr internal field present.
- A geo field stored as a `"lat,lon"` string in a `geo_point` mapping.

Flag any of the above as a **Breaking** incompatibility and surface it to the user before proceeding.
