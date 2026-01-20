# Field Type Conversion for Source Reconstruction

This document describes how RFS reconstructs document `_source` from Lucene index structures when `_source` is disabled or has excluded fields.

## Overview

When `_source` is not available, RFS reconstructs document JSON using a three-tier fallback:

1. **Stored fields** - Fields with `store: true` (fastest)
2. **Doc values** - Column-oriented storage for sorting/aggregations
3. **Points (BKD trees)** - ES 5+ numeric/IP/date index structures
4. **Terms index** - Boolean fields only (ES 1.x-7.x, OpenSearch)

The challenge is that Lucene stores values in internal formats that differ from the original JSON. This document defines the conversion strategy for each ES/OS field type.

---

## Recovery Methods by Source Version

| Source Version | Stored Fields | Doc Values | Points | Terms | Notes |
|----------------|---------------|------------|--------|-------|-------|
| ES 1.x | ✅ | ✅* | ❌ | ✅ | *Requires explicit `doc_values: true` in mapping |
| ES 2.x | ✅ | ✅ | ❌ | ✅ | Full stored/doc_values support |
| ES 5.x | ✅ | ✅ | ✅ | ✅ | Full support including Points |
| ES 6.x | ✅ | ✅ | ✅ | ✅ | Full support |
| ES 7.x | ✅ | ✅ | ✅ | ✅ | Full support |
| ES 8.x | ✅ | ✅ | ✅ | ❌ | No terms index for boolean (Lucene 9) |
| OS 1.x | ✅ | ✅ | ✅ | ✅ | Full support |
| OS 2.x | ✅ | ✅ | ✅ | ✅ | Full support |

---

## EsFieldType Enum

Field types are grouped by their conversion logic:

```java
public enum EsFieldType {
    NUMERIC,        // byte, short, integer, long, float, double, half_float, token_count
    UNSIGNED_LONG,  // unsigned_long
    SCALED_FLOAT,   // scaled_float
    BOOLEAN,        // boolean
    DATE,           // date
    DATE_NANOS,     // date_nanos
    IP,             // ip
    GEO_POINT,      // geo_point
    STRING,         // keyword, text, string, constant_keyword, version, wildcard, match_only_text, flat_object
    BINARY,         // binary
    UNSUPPORTED     // object, nested, geo_shape, vectors, range types, etc.
}
```

---

## Field Type Reference

### NUMERIC

| ES Types | `byte`, `short`, `integer`, `long`, `float`, `double`, `half_float`, `token_count` |
|----------|-----------------------------------------------------------------------------------|
| Doc Values | SORTED_NUMERIC (long bits) |
| Points | ✅ ES 5+ |
| Stored Fields | ✅ |

**Conversion Logic:**

```java
// Integer types: return as-is
if (type in [byte, short, integer, long]) return longValue;

// Float: stored as sortable int bits
if (type == float) return Float.intBitsToFloat(intValue);

// Double: stored as sortable long bits
if (type == double) return Double.longBitsToDouble(longValue);

// Half-float: 16-bit IEEE 754 binary16 encoding
if (type == half_float) return sortableShortToHalfFloat(shortValue);

// Token count: stored as integer (count of tokens from analyzer)
if (type == token_count) return intValue;
```

**Points Decoding (ES 5+):**
- 4-byte packed int: flip sign bit, decode as int
- 8-byte packed long: flip sign bit, decode as long
- Float/double: decode int/long, then convert bits to float/double

---

### UNSIGNED_LONG

| ES Types | `unsigned_long` (OpenSearch 2.8+) |
|----------|-----------------------------------|
| Doc Values | SORTED_NUMERIC (signed long) |
| Points | ❌ Not supported |
| Stored Fields | ✅ |

**Conversion:**

```java
if (longValue >= 0) return longValue;
// Negative means value > Long.MAX_VALUE
return BigInteger.valueOf(longValue).and(UNSIGNED_LONG_MASK);
```

---

### SCALED_FLOAT

| ES Types | `scaled_float` |
|----------|----------------|
| Doc Values | SORTED_NUMERIC (scaled long) |
| Points | ❌ Not supported |
| Stored Fields | ✅ |
| Required Mapping | `scaling_factor` |

**Conversion:**

```java
return (double) longValue / scalingFactor;
```

---

### BOOLEAN

| ES Types | `boolean` |
|----------|-----------|
| Doc Values | SORTED_NUMERIC (0 or 1) - ES 2+ |
| Points | ❌ |
| Terms Index | ✅ ES 1.x-7.x, OpenSearch (stored as "T"/"F") |
| Stored Fields | ✅ (stored as "T"/"F") |

**Conversion:**

```java
// From doc_values (ES 2+)
return longValue != 0;

// From stored fields or terms index
if ("T".equals(value)) return true;
if ("F".equals(value)) return false;
```

**Version Notes:**
- ES 1.x: No doc_values for boolean, use terms index or stored fields
- ES 8+: No terms index (Lucene 9 change), must use doc_values or stored fields

---

### DATE

| ES Types | `date` |
|----------|--------|
| Doc Values | SORTED_NUMERIC (epoch milliseconds) |
| Points | ✅ ES 5+ |
| Stored Fields | ✅ |
| Optional Mapping | `format` (default: `strict_date_optional_time||epoch_millis`) |

**Conversion:**

```java
if ("epoch_millis".equals(format)) return epochMillis;
return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis));
```

---

### DATE_NANOS

| ES Types | `date_nanos` (ES 7+) |
|----------|----------------------|
| Doc Values | SORTED_NUMERIC (epoch nanoseconds) |
| Points | ✅ |
| Stored Fields | ✅ |

**Conversion:**

```java
long epochSecond = epochNanos / 1_000_000_000L;
int nanoAdjustment = (int) (epochNanos % 1_000_000_000L);
return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(epochSecond, nanoAdjustment));
```

---

### IP

| ES Types | `ip` |
|----------|------|
| Doc Values | SORTED_SET (16-byte IPv6 representation) - ES 5+ |
| Doc Values | SORTED_NUMERIC (32-bit IPv4) - ES 2.x |
| Points | ✅ ES 5+ (16-byte packed) |
| Stored Fields | ✅ |

**Conversion (ES 5+):**

```java
byte[] bytes = ...; // 16 bytes

// Check for IPv4-mapped IPv6 (::ffff:x.x.x.x)
boolean isIpv4Mapped = true;
for (int i = 0; i < 10; i++) {
    if (bytes[i] != 0) { isIpv4Mapped = false; break; }
}
if (isIpv4Mapped && bytes[10] == (byte)0xff && bytes[11] == (byte)0xff) {
    return String.format("%d.%d.%d.%d",
        bytes[12] & 0xFF, bytes[13] & 0xFF,
        bytes[14] & 0xFF, bytes[15] & 0xFF);
}
return InetAddress.getByAddress(bytes).getHostAddress();
```

**Conversion (ES 2.x):**

```java
// IPv4 stored as 32-bit integer
return String.format("%d.%d.%d.%d",
    (ipLong >> 24) & 0xFF, (ipLong >> 16) & 0xFF,
    (ipLong >> 8) & 0xFF, ipLong & 0xFF);
```

---

### GEO_POINT

| ES Types | `geo_point` |
|----------|-------------|
| Doc Values | SORTED_NUMERIC (Morton-encoded) - ES 2+ |
| Doc Values | BINARY (2 doubles: lat, lon) - ES 1.x |
| Points | ❌ Complex encoding |
| Stored Fields | ❌ Let doc_values handle |

**Conversion (ES 2+):**

```java
// Morton decoding (geohash format)
double lon = decodeLongitude(encoded);
double lat = decodeLatitude(encoded);
return Map.of("lat", lat, "lon", lon);
```

**Conversion (ES 1.x binary format):**

```java
// 16 bytes: 2 little-endian doubles
ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
double lat = buf.getDouble();
double lon = buf.getDouble();
return Map.of("lat", lat, "lon", lon);
```

---

### STRING

| ES Types | `keyword`, `text`, `string` (ES 1.x-4.x), `constant_keyword`, `version`, `wildcard`, `match_only_text`, `flat_object` |
|----------|-----------------------------------------------------------------------------------------------------------------------|
| Doc Values | SORTED/SORTED_SET (UTF-8 bytes) - keyword only |
| Points | ❌ |
| Stored Fields | ✅ |

**Conversion:**

```java
// Standard string types
return bytesRef.utf8ToString();

// Wildcard type (ES 7.11+): stored as binary doc_values
byte[] decoded = Base64.getDecoder().decode(base64Value);
return new String(decoded, StandardCharsets.UTF_8);
```

**Special Cases:**

| Type | Notes |
|------|-------|
| `text` | Doc values disabled by default |
| `string` (ES 1.x-4.x) | Maps to `keyword` on target, requires `index: not_analyzed` |
| `constant_keyword` | Value stored in mapping, NOT recoverable from index |
| `wildcard` | Always has doc_values internally, uses base64 encoding |

---

### BINARY

| ES Types | `binary` |
|----------|----------|
| Doc Values | BINARY (VInt encoded) - ES 2+ |
| Points | ❌ |
| Stored Fields | ✅ |

**Conversion:**

```java
// From stored fields or doc_values
return Base64.getEncoder().encodeToString(bytes);
```

**Doc Values Format (VInt encoding):**
```
[VInt:count][VInt:len][bytes]...
```

---

### UNSUPPORTED

These types cannot be reconstructed from doc_values:

| Type | Reason |
|------|--------|
| `object` | Flattened structure, no single doc_value |
| `nested` | Stored as separate hidden documents |
| `flat_object` | Dynamic keys, no fixed schema |
| `join` | Parent/child relationship metadata |
| `geo_shape` | Complex geometry (polygons, etc.) |
| `xy_shape` | Complex 2D geometry |
| `xy_point` | Not implemented |
| `completion` | FST-based suggester structure |
| `search_as_you_type` | Multi-field with edge n-grams |
| `knn_vector` | Dense float array, not in doc_values |
| `sparse_vector` | Sparse map structure |
| `percolator` | Stored query, not data |
| `rank_feature` | Scoring boost only |
| `rank_features` | Sparse scoring map |
| `alias` | Virtual field pointing to another |
| `derived` | Computed at query time |
| `integer_range` | Complex binary format |
| `long_range` | Complex binary format |
| `float_range` | Complex binary format |
| `double_range` | Complex binary format |
| `date_range` | Complex binary format |
| `ip_range` | Complex binary format |

---

## Field Type Support Matrix

| Field Type | Doc Values | Points | Stored | Terms | Version |
|------------|------------|--------|--------|-------|---------|
| `byte` | ✅ | ✅ ES5+ | ✅ | ❌ | All |
| `short` | ✅ | ✅ ES5+ | ✅ | ❌ | All |
| `integer` | ✅ | ✅ ES5+ | ✅ | ❌ | All |
| `long` | ✅ | ✅ ES5+ | ✅ | ❌ | All |
| `float` | ✅ | ✅ ES5+ | ✅ | ❌ | ES 5+ |
| `double` | ✅ | ✅ ES5+ | ✅ | ❌ | ES 5+ |
| `half_float` | ✅ | ✅ ES5+ | ✅ | ❌ | ES 5+ |
| `scaled_float` | ✅ | ❌ | ✅ | ❌ | ES 5+ |
| `unsigned_long` | ✅ | ❌ | ✅ | ❌ | OS 2.8+ |
| `token_count` | ✅ | ✅ ES5+ | ✅ | ❌ | ES 5+ |
| `boolean` | ✅ ES2+ | ❌ | ✅ | ✅* | All |
| `date` | ✅ | ✅ ES5+ | ✅ | ❌ | ES 2+ |
| `date_nanos` | ✅ | ✅ | ✅ | ❌ | ES 7+ |
| `ip` | ✅ | ✅ ES5+ | ✅ | ❌ | ES 2+ |
| `geo_point` | ✅ | ❌ | ❌ | ❌ | All |
| `keyword` | ✅ | ❌ | ✅ | ❌ | ES 5+ |
| `text` | ❌** | ❌ | ✅ | ❌ | ES 5+ |
| `string` | ✅ | ❌ | ✅ | ❌ | ES 1.x-4.x |
| `constant_keyword` | ❌*** | ❌ | ❌ | ❌ | ES 7.11+ |
| `wildcard` | ✅ | ❌ | ✅ | ❌ | ES 7.11+ |
| `binary` | ✅ ES2+ | ❌ | ✅ | ❌ | All |

\* Terms index not available in ES 8+ (Lucene 9)
\*\* Text fields have doc_values disabled by default
\*\*\* constant_keyword value is in mapping, not index - NOT recoverable

---

## Architecture

### FieldMappingInfo Record

```java
public record FieldMappingInfo(
    EsFieldType type,
    String mappingType,      // Original type (e.g., "float", "double")
    String format,           // For DATE, DATE_NANOS
    Double scalingFactor,    // For SCALED_FLOAT
    boolean docValues        // Whether doc_values is enabled
) {}
```

### FieldMappingContext

Parses index mappings once per work item and provides field type info:

```java
public class FieldMappingContext {
    public FieldMappingInfo getFieldInfo(String fieldName);
    public Set<String> getFieldNames();
}
```

### SourceReconstructor Flow

```
reconstructSource(reader, docId, document, mappingContext)
    │
    ├─► 1. Read stored fields (fastest)
    │       └─► getStoredFieldValue(field, mappingInfo)
    │
    ├─► 2. Read doc_values (for fields not in stored)
    │       └─► reader.getDocValue(docId, fieldInfo)
    │       └─► convertDocValue(value, fieldInfo, mappingInfo)
    │
    └─► 3. Fallback: Points or Terms (for remaining fields)
            └─► reader.getValueFromPointsOrTerms(docId, fieldName, fieldType)
            └─► convertFallbackValue(value, mappingInfo)
```

---

## Example Mapping → Conversion

Given this mapping:
```json
{
  "properties": {
    "timestamp": { "type": "date", "format": "yyyy-MM-dd HH:mm:ss" },
    "price": { "type": "scaled_float", "scaling_factor": 100 },
    "active": { "type": "boolean" },
    "client_ip": { "type": "ip" },
    "location": { "type": "geo_point" },
    "tags": { "type": "keyword" }
  }
}
```

Reconstruction:

| Field | Lucene Value | Converted JSON Value |
|-------|--------------|---------------------|
| `timestamp` | `1705234567000` | `"2024-01-14T12:34:27Z"` |
| `price` | `1999` | `19.99` |
| `active` | `1` | `true` |
| `client_ip` | `[0,0,0,0,0,0,0,0,0,0,ff,ff,c0,a8,1,1]` | `"192.168.1.1"` |
| `location` | `4234567890123456789` | `{"lat": 40.7128, "lon": -74.0060}` |
| `tags` | `"production"` | `"production"` |

---

## Files

| File | Purpose |
|------|---------|
| `lucene/EsFieldType.java` | Field type enum with mapping type lookup |
| `lucene/FieldMappingInfo.java` | Record holding conversion parameters |
| `lucene/FieldMappingContext.java` | Parses mappings, provides field info |
| `lucene/SourceReconstructor.java` | Main reconstruction logic |
| `lucene/LuceneLeafReader.java` | Interface for doc_value/Points access |
| `lucene/version_*/LeafReader*.java` | Version-specific implementations |
| `worker/RegularDocumentReaderEngine.java` | Wires mapping context to reader |
