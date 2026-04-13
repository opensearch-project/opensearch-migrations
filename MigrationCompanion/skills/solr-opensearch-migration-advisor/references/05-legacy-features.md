# Solr Legacy Features: Migration Strategies for OpenSearch

This document covers Solr features that **cannot be directly migrated** to OpenSearch and require a strategy change. See also `05b-legacy-features-continued.md` for joins, streaming expressions, spell check, MLT, custom handlers, and the summary table.

## 1. Data Import Handler (DIH)

### 1.1 What It Is

Solr's Data Import Handler (`solr.DataImportHandler`) pulls data from relational databases (via JDBC), XML files, or other sources and indexes it into Solr. It is configured via `data-config.xml` and supports full imports, delta imports, and scheduled incremental updates.

```xml
<!-- solrconfig.xml -->
<requestHandler name="/dataimport" class="solr.DataImportHandler">
  <lst name="defaults">
    <str name="config">data-config.xml</str>
  </lst>
</requestHandler>
```

```xml
<!-- data-config.xml -->
<dataConfig>
  <dataSource type="JdbcDataSource" driver="com.mysql.jdbc.Driver"
              url="jdbc:mysql://localhost/mydb" user="root" password="secret"/>
  <document>
    <entity name="product" query="SELECT id, title, price FROM products"
            deltaQuery="SELECT id FROM products WHERE last_modified > '${dih.last_index_time}'"
            deltaImportQuery="SELECT id, title, price FROM products WHERE id='${dih.delta.id}'">
      <field column="id" name="id"/>
      <field column="title" name="title"/>
      <field column="price" name="price"/>
    </entity>
  </document>
</dataConfig>
```

### 1.2 Why There Is No Direct Equivalent

OpenSearch does not have a built-in data ingestion handler. All data must be pushed via its REST API (Bulk API or individual index requests).

### 1.3 Migration Strategy

| Approach | Description |
| :--- | :--- |
| **Custom script** | Write a Python/Java/Go script that queries the database and calls the OpenSearch Bulk API. |
| **Logstash** | Use the Logstash JDBC input plugin and the OpenSearch output plugin. |
| **Apache Kafka + Kafka Connect** | Use the JDBC Source Connector and the OpenSearch Sink Connector. |
| **AWS Glue / Apache Spark** | For large-scale batch migrations, use a distributed ETL framework. |
| **OpenSearch Ingestion (OSI)** | AWS-managed pipeline service (Data Prepper) for Amazon OpenSearch Service. |

**Delta import equivalent**: Use CDC (Debezium), a `last_modified` timestamp column polled by your ETL script, or database triggers.

**Example: Bulk API indexing (Python)**
```python
from opensearchpy import OpenSearch, helpers

client = OpenSearch(hosts=["https://localhost:9200"])

actions = [
    {"_index": "products", "_id": row["id"], "_source": row}
    for row in fetch_from_db()
]
helpers.bulk(client, actions)
```

---

## 2. BlockJoin (Nested / Parent-Child Documents)

### 2.1 What It Is

Solr's `BlockJoin` indexes parent and child documents as a single block. Child documents are indexed adjacent to their parent for one-to-many relationships (e.g., a product with multiple SKUs).

**Solr indexing (JSON):**
```json
{
  "id": "product-1",
  "title": "Widget",
  "_childDocuments_": [
    {"id": "sku-1", "color": "red", "size": "M"},
    {"id": "sku-2", "color": "blue", "size": "L"}
  ]
}
```

**Solr BlockJoin query:** `{!parent which="type:product"}color:red`

### 2.2 Migration Strategy: Option A — Nested Objects

Use OpenSearch `nested` type for tightly coupled parent-child data queried together.

```json
{
  "mappings": {
    "properties": {
      "title": { "type": "text" },
      "skus": {
        "type": "nested",
        "properties": {
          "color": { "type": "keyword" },
          "size":  { "type": "keyword" }
        }
      }
    }
  }
}
```

**Query (find products with a red SKU):**
```json
{
  "query": {
    "nested": {
      "path": "skus",
      "query": { "term": { "skus.color": "red" } }
    }
  }
}
```

**Trade-off**: Updating a single child requires re-indexing the entire parent document.

### 2.3 Migration Strategy: Option B — Parent-Child Join Field

Use the OpenSearch `join` field type for loosely coupled relationships where children are updated independently.

```json
{
  "mappings": {
    "properties": {
      "my_join": { "type": "join", "relations": { "product": "sku" } },
      "title": { "type": "text" },
      "color": { "type": "keyword" }
    }
  }
}
```

**Query (has_child):**
```json
{
  "query": {
    "has_child": {
      "type": "sku",
      "query": { "term": { "color": "red" } }
    }
  }
}
```

**Trade-off**: Requires routing; cross-shard joins are not supported.

### 2.4 Choosing Between Nested and Join

| Criterion | Nested | Join (Parent-Child) |
| :--- | :--- | :--- |
| Child update frequency | Low (full re-index of parent) | High (update child independently) |
| Query performance | Faster | Slower (memory overhead) |
| Relationship cardinality | Moderate | High (millions of children) |

**Option C — Denormalization**: For many use cases, flatten parent and child fields into a single document. This is the simplest approach when child data is read-only or rarely changes.

---

## 3. Function Queries → `function_score`

### 3.1 What They Are

Solr function queries compute a numeric score from field values or expressions:
- **`bf`** in eDisMax: `bf=recip(ms(NOW,last_modified),3.16e-11,1,1)`
- **`_val_`**: `_val_:"recip(rord(price),1,1000,1000)"`
- **`{!func}`**: `{!func}log(popularity)`
- **`boost`** (Solr 4.9+): `boost=recip(ms(NOW,last_modified),3.16e-11,1,1)`

### 3.2 Migration Strategy: `function_score` Query

Wrap the main query in a `function_score` query:

```json
{
  "query": {
    "function_score": {
      "query": { "match_all": {} },
      "functions": [ ... ],
      "score_mode": "sum",
      "boost_mode": "multiply"
    }
  }
}
```

### 3.3 Common Solr Function → OpenSearch Mapping

| Solr Function | OpenSearch Equivalent |
| :--- | :--- |
| `recip(x, m, a, b)` | `script_score`: `params.a / (params.m * x + params.b)` |
| `recip(ms(NOW,date_field),...)` | `gauss` decay function on date field |
| `log(field)` | `script_score`: `Math.log10(doc['field'].value + 1)` |
| `sqrt(field)` | `script_score`: `Math.sqrt(doc['field'].value)` |
| `pow(field,exp)` | `script_score`: `Math.pow(doc['field'].value, params.exp)` |
| `ms(NOW,field)` | `script_score`: `(System.currentTimeMillis() - doc['field'].value.millis)` |
| `ord(field)` / `rord(field)` | No direct equivalent; approximate with `rank_feature` |

### 3.4 Recency Boost Example

**Solr:** `bf=recip(ms(NOW,last_modified),3.16e-11,1,1)`

**OpenSearch (`gauss` decay):**
```json
{
  "query": {
    "function_score": {
      "query": { "multi_match": { "query": "search term", "fields": ["title", "body"] } },
      "functions": [
        { "gauss": { "last_modified": { "origin": "now", "scale": "30d", "decay": 0.5 } } }
      ],
      "boost_mode": "multiply"
    }
  }
}
```

### 3.5 Field Value Factor (Simple Boosts)

For simple field-value boosts (e.g., `bf=log(popularity)`), use `field_value_factor`:

```json
{
  "query": {
    "function_score": {
      "query": { "match": { "title": "widget" } },
      "functions": [
        {
          "field_value_factor": {
            "field": "popularity", "modifier": "log1p", "factor": 1.0, "missing": 1
          }
        }
      ],
      "boost_mode": "multiply"
    }
  }
}
```

Available `modifier` values: `none`, `log`, `log1p`, `log2p`, `ln`, `ln1p`, `ln2p`, `square`, `sqrt`, `reciprocal`.
