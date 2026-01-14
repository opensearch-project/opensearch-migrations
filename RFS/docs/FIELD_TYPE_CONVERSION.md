# Field Type Conversion for Source Reconstruction

This document describes how to convert Lucene doc_values back to Elasticsearch/OpenSearch JSON field values when `_source` is disabled or has excluded fields.

## Overview

When `_source` is not available, RFS reconstructs document JSON from:
1. **Stored fields** - Fields with `store: true`
2. **Doc values** - Column-oriented storage for sorting/aggregations

The challenge is that Lucene stores values in internal formats that differ from the original JSON. This document defines the conversion strategy for each ES/OS field type.

---

## EsFieldType Enum

Field types are grouped by their conversion logic into a minimal enum:

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
    XY_POINT,       // xy_point
    STRING,         // keyword, text, constant_keyword, version, wildcard, match_only_text
    BINARY,         // binary
    RANGE,          // integer_range, long_range, float_range, double_range, date_range, ip_range
    UNSUPPORTED     // object, nested, geo_shape, vectors, etc.
}
```

---

## Conversion Reference

### NUMERIC
**ES Types:** `byte`, `short`, `integer`, `long`, `float`, `double`, `half_float`, `token_count`

**Lucene Storage:** SORTED_NUMERIC (long bits)

**Conversion:** None - return value as-is

```java
// float/double stored as long bits
if (originalType is float/double) {
    return Double.longBitsToDouble(longValue);
}
return longValue;
```

---

### UNSIGNED_LONG
**ES Types:** `unsigned_long`

**Lucene Storage:** SORTED_NUMERIC (signed long)

**Conversion:** Interpret as unsigned

```java
if (longValue >= 0) {
    return longValue;
}
// Negative means value > Long.MAX_VALUE
return BigInteger.valueOf(longValue).and(UNSIGNED_MASK);
```

---

### SCALED_FLOAT
**ES Types:** `scaled_float`

**Lucene Storage:** SORTED_NUMERIC (long)

**Conversion:** Divide by `scaling_factor` from mapping

**Required Mapping Info:** `scaling_factor` (double)

```java
return (double) longValue / scalingFactor;
```

---

### BOOLEAN
**ES Types:** `boolean`

**Lucene Storage:** SORTED_NUMERIC (0 or 1)

**Conversion:** 0 → false, non-zero → true

```java
return longValue != 0;
```

---

### DATE
**ES Types:** `date`

**Lucene Storage:** SORTED_NUMERIC (epoch milliseconds)

**Conversion:** Format to string using `format` from mapping

**Optional Mapping Info:** `format` (default: `strict_date_optional_time||epoch_millis`)

```java
Instant instant = Instant.ofEpochMilli(longValue);
// If format is "epoch_millis", return as number
// Otherwise format as ISO8601 or custom format
return DateTimeFormatter.ISO_INSTANT.format(instant);
```

---

### DATE_NANOS
**ES Types:** `date_nanos`

**Lucene Storage:** SORTED_NUMERIC (epoch nanoseconds)

**Conversion:** Format to string with nanosecond precision

**Optional Mapping Info:** `format`

```java
long epochSecond = longValue / 1_000_000_000;
int nanoAdjustment = (int) (longValue % 1_000_000_000);
Instant instant = Instant.ofEpochSecond(epochSecond, nanoAdjustment);
return DateTimeFormatter.ISO_INSTANT.format(instant);
```

---

### IP
**ES Types:** `ip`

**Lucene Storage:** SORTED_SET (16-byte IPv6 representation)

**Conversion:** Decode bytes to IP string

```java
byte[] bytes = bytesRef.bytes; // 16 bytes

// Check for IPv4-mapped IPv6 (::ffff:x.x.x.x)
boolean isIpv4 = true;
for (int i = 0; i < 10; i++) {
    if (bytes[i] != 0) { isIpv4 = false; break; }
}
if (isIpv4 && bytes[10] == (byte)0xff && bytes[11] == (byte)0xff) {
    // IPv4
    return String.format("%d.%d.%d.%d",
        bytes[12] & 0xFF, bytes[13] & 0xFF,
        bytes[14] & 0xFF, bytes[15] & 0xFF);
}
// IPv6
return InetAddress.getByAddress(bytes).getHostAddress();
```

---

### GEO_POINT
**ES Types:** `geo_point`

**Lucene Storage:** SORTED_NUMERIC (Morton-encoded lat/lon)

**Conversion:** Decode to `{lat, lon}` object

```java
// ES uses GeoEncodingUtils for Morton encoding
int latBits = (int) (longValue >> 32);
int lonBits = (int) longValue;
double lat = GeoEncodingUtils.decodeLatitude(latBits);
double lon = GeoEncodingUtils.decodeLongitude(lonBits);
return Map.of("lat", lat, "lon", lon);
```

---

### XY_POINT
**ES Types:** `xy_point`

**Lucene Storage:** SORTED_NUMERIC (encoded x/y)

**Conversion:** Decode to `{x, y}` object

```java
int xBits = (int) (longValue >> 32);
int yBits = (int) longValue;
float x = Float.intBitsToFloat(xBits);
float y = Float.intBitsToFloat(yBits);
return Map.of("x", x, "y", y);
```

---

### STRING
**ES Types:** `keyword`, `text`, `constant_keyword`, `version`, `wildcard`, `match_only_text`

**Lucene Storage:** SORTED or SORTED_SET (UTF-8 bytes)

**Conversion:** None - already a string

```java
return bytesRef.utf8ToString();
```

**Note:** `text` fields typically don't have doc_values enabled by default.

---

### BINARY
**ES Types:** `binary`

**Lucene Storage:** BINARY (raw bytes)

**Conversion:** Base64 encode

```java
return Base64.getEncoder().encodeToString(bytes);
```

---

### RANGE
**ES Types:** `integer_range`, `long_range`, `float_range`, `double_range`, `date_range`, `ip_range`

**Lucene Storage:** Multiple SORTED_NUMERIC fields (from/to)

**Conversion:** Reconstruct range object

**Optional Mapping Info:** `format` (for `date_range`)

```java
// Stored as fieldName.from and fieldName.to
return Map.of(
    "gte", fromValue,
    "lte", toValue
);
```

---

### UNSUPPORTED
**ES Types:** Cannot be reconstructed from doc_values

| Type | Reason |
|------|--------|
| `object` | Flattened structure, no single doc_value |
| `nested` | Stored as separate hidden documents |
| `flat_object` | Dynamic keys, no fixed schema |
| `join` | Parent/child relationship metadata |
| `geo_shape` | Complex geometry (polygons, etc.) |
| `xy_shape` | Complex 2D geometry |
| `completion` | FST-based suggester structure |
| `search_as_you_type` | Multi-field with edge n-grams |
| `knn_vector` | Dense float array, not in doc_values |
| `sparse_vector` | Sparse map structure |
| `percolator` | Stored query, not data |
| `rank_feature` | Scoring boost only |
| `rank_features` | Sparse scoring map |
| `alias` | Virtual field pointing to another |
| `derived` | Computed at query time |
| `star_tree` | Pre-aggregated index structure |
| `semantic` | Wraps embedding field |

**Conversion:** Return `null` or skip field

---

## FieldMappingInfo Record

Minimal structure to hold conversion parameters:

```java
public record FieldMappingInfo(
    EsFieldType type,
    String format,           // DATE, DATE_NANOS, date_range
    Double scalingFactor     // SCALED_FLOAT
) {
    public static FieldMappingInfo from(JsonNode fieldMapping) {
        String typeStr = fieldMapping.path("type").asText(null);
        EsFieldType type = EsFieldType.fromMappingType(typeStr);
        
        String format = fieldMapping.has("format") 
            ? fieldMapping.get("format").asText() 
            : null;
            
        Double scalingFactor = fieldMapping.has("scaling_factor")
            ? fieldMapping.get("scaling_factor").asDouble()
            : null;
            
        return new FieldMappingInfo(type, format, scalingFactor);
    }
}
```

---

## Integration with SourceReconstructor

### Current Flow
```
LuceneReader.getDocument()
    → SourceReconstructor.reconstructSource()
        → reader.getDocValueFields()
        → reader.getDocValue(docId, fieldInfo)
        → convertDocValue(value, fieldInfo)  // Heuristic-based
```

### Enhanced Flow
```
LuceneReader.getDocument(mappingContext)
    → SourceReconstructor.reconstructSource(reader, docId, document, mappingContext)
        → reader.getDocValueFields()
        → reader.getDocValue(docId, fieldInfo)
        → FieldMappingInfo info = mappingContext.getFieldInfo(fieldName)
        → convertDocValue(value, info)  // Type-aware conversion
```

### Key Changes Required

1. **Pass IndexMetadata to document reading chain**
   - `RegularDocumentReaderEngine` receives `IndexMetadata.Factory`
   - Creates `FieldMappingContext` from `indexMetadata.getMappings()`

2. **Create FieldMappingContext**
   - Parses mappings JSON once per index
   - Provides `FieldMappingInfo getFieldInfo(String fieldName)`
   - Handles nested field paths (e.g., `user.address.city`)

3. **Update SourceReconstructor**
   - Accept `FieldMappingContext` parameter
   - Use `EsFieldType` for conversion dispatch
   - Apply format/scalingFactor when needed

---

## Files to Modify

| File | Change |
|------|--------|
| `lucene/EsFieldType.java` | New enum (create) |
| `lucene/FieldMappingInfo.java` | New record (create) |
| `lucene/FieldMappingContext.java` | New class (create) |
| `lucene/SourceReconstructor.java` | Add type-aware conversion |
| `lucene/LuceneReader.java` | Pass mapping context |
| `worker/RegularDocumentReaderEngine.java` | Accept IndexMetadata.Factory |
| `common/DocumentReaderEngine.java` | Update interface |
| `RfsMigrateDocuments.java` | Wire dependencies |

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
| `timestamp` | `1705234567000` | `"2024-01-14 12:34:27"` |
| `price` | `1999` | `19.99` |
| `active` | `1` | `true` |
| `client_ip` | `[0,0,0,0,0,0,0,0,0,0,ff,ff,c0,a8,1,1]` | `"192.168.1.1"` |
| `location` | `4234567890123456789` | `{"lat": 40.7128, "lon": -74.0060}` |
| `tags` | `"production"` | `"production"` |
