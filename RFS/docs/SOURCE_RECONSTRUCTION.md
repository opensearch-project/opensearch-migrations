# Source Reconstruction

When `_source` is disabled or has excluded fields, RFS reconstructs document JSON from Lucene's internal storage. This document describes the reconstruction process and its limitations.

## How It Works

RFS attempts to recover field values using a three-tier fallback:

1. **Stored Fields** - Fields with `store: true` in the mapping
2. **Doc Values** - Column-oriented storage used for sorting/aggregations (enabled by default since ES 2.0 for most field types)
3. **Points (BKD Trees)** - Indexed numeric data used for range queries (ES 5.0+)

Each tier has different precision characteristics and version support.

## Recovery Tiers

### Tier 1: Stored Fields
- **Precision:** Lossless - original value preserved exactly
- **Performance:** Fast - direct field lookup
- **Availability:** All ES/OS versions
- **Limitation:** Only available if `store: true` was set in mapping

### Tier 2: Doc Values
- **Precision:** Type-dependent (see below)
- **Performance:** Moderate - requires type conversion
- **Availability:** ES 2.x+ (enabled by default), ES 1.x (if explicitly enabled)
- **Limitation:** Some types lose precision or require mapping info
- **Note:** Enabled by default for all field types except `text` since ES 2.0

### Tier 3: Points (BKD Trees)
- **Precision:** Type-dependent, generally lower than doc_values
- **Performance:** Slower - requires tree traversal
- **Availability:** ES 5.x+ (when Points indexing is enabled)
- **Limitation:** Only numeric types (int, long, float, double, date, IP)

## Precision by Field Type

| Field Type | Stored | Doc Values | Points | Notes |
|------------|--------|------------|--------|-------|
| keyword/text | ✅ Exact | ✅ Exact | ❌ N/A | |
| integer/long | ✅ Exact | ✅ Exact | ✅ Exact | |
| float/double | ✅ Exact | ✅ Exact | ⚠️ Reduced | Points use 4-byte encoding |
| boolean | ✅ Exact | ✅ Exact | ❌ N/A | Stored as T/F or 0/1 |
| date | ✅ Exact | ✅ Exact | ✅ Exact | Converted to ISO format |
| date_nanos | ✅ Exact | ✅ Exact | ✅ Exact | Nanosecond precision preserved |
| ip | ✅ Exact | ✅ Exact | ✅ Exact | IPv4/IPv6 supported |
| geo_point | ✅ Exact | ⚠️ Reduced | ❌ N/A | Doc values lose ~1cm precision |
| scaled_float | ✅ Exact | ✅ Exact | ❌ N/A | Requires scaling_factor from mapping |
| unsigned_long | ✅ Exact | ✅ Exact | ❌ N/A | Values > Long.MAX_VALUE supported |
| binary | ✅ Exact | ❌ N/A | ❌ N/A | Base64 encoded |

## Version Support Matrix

| Source Version | Stored Fields | Doc Values | Points |
|----------------|---------------|------------|--------|
| ES 1.x | ✅ | ❌ | ❌ |
| ES 2.x | ✅ | ✅ | ❌ |
| ES 5.x+ | ✅ | ✅ | ✅ |
| OS 1.x+ | ✅ | ✅ | ✅ |

## Mapping Requirements

Some field types require mapping information for accurate reconstruction:

- **scaled_float**: Needs `scaling_factor` to convert internal long back to decimal
- **date/date_nanos**: Uses `format` to determine output format (defaults to ISO)
- **geo_point**: Requires type info to decode packed long representation

RFS fetches index mappings once per work item and uses them during reconstruction.

## Recommendations

For best reconstruction fidelity:

1. **Use `store: true`** for critical fields - guarantees lossless recovery
2. **Keep `doc_values: true`** (default) - provides fallback for most types
3. **Avoid disabling `_source`** if possible - simplest migration path

## Limitations

- **Nested objects**: Not reconstructed (flattened in Lucene)
- **Multi-valued fields**: Order may not be preserved from doc_values
- **Object fields**: Only leaf fields are recovered
- **Geo shapes**: Not supported (complex geometry)
- **Dense vectors**: Not supported
