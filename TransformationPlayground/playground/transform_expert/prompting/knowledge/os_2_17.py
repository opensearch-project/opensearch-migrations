INDEX_GUIDANCE = """"""

INDEX_KNOWLEDGE = """
# Creating an Index in OpenSearch

## Overview

OpenSearch allows you to create an empty index for future use, in addition to creating an index based on existing documents. This feature, introduced in version 1.0, provides flexibility in index management and configuration.

## Index Creation Process

### HTTP Method and Endpoint

To create an index, use the following HTTP request:

```
PUT /<index>
```

Replace `<index>` with your desired index name.

### Index Naming Conventions

When naming your index, adhere to these rules:

- Use only lowercase letters
- Do not start with underscores (`_`) or hyphens (`-`)
- Avoid spaces, commas, and the following characters: `:`, `"`, `*`, `+`, `/`, `\`, `|`, `?`, `#`, `>`, `<`

### Request Parameters

#### Path Parameters

| Parameter | Type   | Description                     | Required |
|-----------|--------|---------------------------------|----------|
| index     | String | The name of the index to create | Yes      |

#### Query Parameters

All query parameters are optional:

| Parameter               | Type   | Description                                                                                                   | Default |
|-------------------------|--------|---------------------------------------------------------------------------------------------------------------|---------|
| wait_for_active_shards  | String | Number of active shards required before processing the request                                                | 1       |
| cluster_manager_timeout | Time   | Maximum wait time for a connection to the cluster manager node                                                | 30s     |
| timeout                 | Time   | Maximum wait time for the request to return                                                                   | 30s     |

Note: Setting `wait_for_active_shards` to `all` or a value greater than 1 requires replicas to be configured. For example, if you specify a value of 3, the index must have two replicas distributed across two additional nodes for the request to succeed.

### Request Body

The request body can include the following optional components:

- `settings`: Configure index-specific settings
- `mappings`: Define the structure of documents in the index
- `aliases`: Set up index aliases

## Example Request

Here's an example of creating an index with custom settings, mappings, and an alias:

```json
PUT /sample-index
{
  "settings": {
    "index": {
      "number_of_shards": 2,
      "number_of_replicas": 1
    }
  },
  "mappings": {
    "properties": {
      "age": {
        "type": "integer"
      }
    }
  },
  "aliases": {
    "sample-alias1": {}
  }
}
```

This request creates an index named `sample-index` with 2 shards and 1 replica, defines a mapping for an `age` field of type integer, and creates an alias named `sample-alias1`.# Index Settings in OpenSearch

# Overview of Index Settings

Index settings in OpenSearch are categorized as:

1. Cluster-level settings: Affect all indexes in the cluster
2. Index-level settings: Apply to individual indexes

Both categories include static (require restart) and dynamic (updateable on-the-fly) settings.

## Cluster-Level Index Settings

### Static Cluster-Level Settings

Require a cluster restart to apply changes:

1. `indices.cache.cleanup_interval`: 
   - Purpose: Schedules cache cleanup for expired entries
   - Default: 1 minute
   - Format: Time unit (e.g., "1m")

2. `indices.requests.cache.size`: 
   - Purpose: Sets the cache size as a percentage of heap size
   - Default: 1% of heap
   - Format: String (e.g., "1%")

### Dynamic Cluster-Level Settings

Can be updated without restarting the cluster:

1. `action.auto_create_index`: Enables automatic index creation
2. `action.destructive_requires_name`: Requires explicit index names for deletion
3. `cluster.default.index.refresh_interval`: Sets default refresh interval for indexes
4. `cluster.minimum.index.refresh_interval`: Establishes minimum refresh interval
5. `cluster.indices.close.enable`: Allows closing of open indexes
6. `indices.recovery.max_bytes_per_sec`: Limits recovery traffic
7. `indices.recovery.max_concurrent_file_chunks`: Controls parallel file chunk transfers
8. `indices.recovery.max_concurrent_operations`: Sets parallel recovery operations
9. `indices.recovery.max_concurrent_remote_store_streams`: Manages parallel remote store streams
10. `indices.replication.max_bytes_per_sec`: Caps replication traffic
11. `indices.fielddata.cache.size`: Defines field data cache size
12. `indices.query.bool.max_clause_count`: Sets maximum queryable fields and terms
13. `cluster.remote_store.index.path.type`: Determines remote store data path strategy
14. `cluster.remote_store.index.path.hash_algorithm`: Specifies hash function for remote store paths
15. `cluster.remote_store.translog.transfer_timeout`: Sets timeout for remote store translog uploads
16. `cluster.remote_store.index.segment_metadata.retention.max_count`: Controls segment metadata retention
17. `cluster.remote_store.segment.transfer_timeout`: Manages timeout for remote store segment updates
18. `cluster.remote_store.translog.path.prefix`: Sets translog path prefix in remote stores
19. `cluster.remote_store.segments.path.prefix`: Defines segment data path prefix in remote stores
20. `cluster.snapshot.shard.path.prefix`: Controls snapshot shard-level blob path prefix

## Index-Level Settings

### Specifying Settings at Index Creation

Example:
```json
PUT /new_index
{
  "settings": {
    "index.number_of_shards": 3,
    "index.number_of_replicas": 2
  }
}
```

### Static Index-Level Settings

Require closing and reopening the index to apply changes:

1. `index.number_of_shards`: Sets primary shard count
2. `index.number_of_routing_shards`: Defines routing shard count
3. `index.shard.check_on_startup`: Controls shard corruption checks
4. `index.codec`: Determines compression method for stored fields
5. `index.codec.compression_level`: Sets compression level for supported codecs
6. `index.codec.qatmode`: Specifies hardware acceleration mode for QAT codecs
7. `index.routing_partition_size`: Controls custom routing shard subset size
8. `index.soft_deletes.retention_lease.period`: Sets retention period for shard operation history
9. `index.load_fixed_bitset_filters_eagerly`: Manages preloading of cached filters
10. `index.hidden`: Controls index visibility in wildcard queries
11. `index.merge.policy`: Defines Lucene segment merge policy
12. `index.merge_on_flush.enabled`: Enables/disables merge-on-refresh feature
13. `index.merge_on_flush.max_full_flush_merge_wait_time`: Sets wait time for merges during flush
14. `index.merge_on_flush.policy`: Specifies merge policy for merge-on-flush
15. `index.check_pending_flush.enabled`: Controls pending flush checks on updates
16. `index.use_compound_file`: Manages compound file usage for new segments

### Updating Static Index Settings

To update a static setting:
1. Close the index
2. Apply the new setting
3. Reopen the index

Example:
```json
POST /target_index/_close
PUT /target_index/_settings
{
  "index": {
    "codec": "zstd_no_dict",
    "codec.compression_level": 3
  }
}
POST /target_index/_open
```

### Dynamic Index-Level Settings

Can be updated at any time:

1. `index.number_of_replicas`: Sets replica shard count
2. `index.auto_expand_replicas`: Enables automatic replica adjustment
3. `index.search.idle.after`: Defines idle time for shards
4. `index.search.default_pipeline`: Specifies default search pipeline
5. `index.refresh_interval`: Controls index refresh frequency
6. `index.max_result_window`: Limits search result window
7. `index.max_inner_result_window`: Caps nested search hits
8. `index.max_rescore_window`: Sets rescore request window limit
9. `index.max_docvalue_fields_search`: Restricts docvalue fields in queries
10. `index.max_script_fields`: Limits script fields in queries
11. `index.max_ngram_diff`: Sets NGram tokenizer/filter difference limit
12. `index.max_shingle_diff`: Defines shingle size difference limit
13. `index.max_refresh_listeners`: Caps refresh listeners per shard
14. `index.analyze.max_token_count`: Limits tokens from _analyze API
15. `index.highlight.max_analyzed_offset`: Sets character limit for highlight analysis
16. `index.max_terms_count`: Restricts terms in terms queries
17. `index.max_regex_length`: Limits regex length in queries
18. `index.query.default_field`: Specifies default query fields
19. `index.query.max_nested_depth`: Sets maximum nesting for nested queries
20. `index.requests.cache.enable`: Toggles index request cache
21. `index.routing.allocation.enable`: Controls shard allocation
22. `index.routing.rebalance.enable`: Manages shard rebalancing
23. `index.gc_deletes`: Sets retention time for deleted document versions
24. `index.default_pipeline`: Defines default ingest pipeline
25. `index.final_pipeline`: Specifies final ingest pipeline
26. `index.optimize_doc_id_lookup.fuzzy_set.enabled`: Enables Bloom filter for ID lookups
27. `index.optimize_doc_id_lookup.fuzzy_set.false_positive_probability`: Sets Bloom filter false positive rate

### Updating Dynamic Index Settings

Example:
```json
PUT /target_index/_settings
{
  "index": {
    "refresh_interval": "5s"
  }
}
```

# Introduction to Mappings

Mappings in OpenSearch define how documents and their fields are stored and indexed. They play a crucial role in determining the structure of your data and optimizing search performance. While OpenSearch offers dynamic mapping capabilities, explicit mappings are often preferred for maintaining data consistency and enhancing performance, especially in large-scale operations.

## Dynamic Mapping

### Overview

Dynamic mapping allows OpenSearch to automatically add fields to an index when a document is indexed. This feature provides flexibility but may lead to unexpected data structures.

### Dynamic Mapping Types

OpenSearch supports various data types for dynamic mapping:

| Type | Description |
|------|-------------|
| Null | Unindexed and unsearchable |
| Boolean | Accepts `true` and `false` (empty string equals `false`) |
| Float | 32-bit floating-point number |
| Double | 64-bit floating-point number |
| Integer | Signed 32-bit number |
| Object | JSON objects with their own fields and mappings |
| Array | Set of values of the same data type (e.g., integers, strings) |
| Text | Full-text string values |
| Keyword | Structured string values (e.g., email addresses, ZIP codes) |
| Date detection string | Automatically processes strings matching date formats as dates |
| Numeric detection string | When enabled, processes numeric strings as appropriate number types |

### Dynamic Templates

Dynamic templates allow for custom mappings of dynamically added fields based on data type, field name, or path. They provide a flexible schema that adapts to changes in data structure.

Example dynamic template:

```json
PUT index {
  "mappings": {
    "dynamic_templates": [
      {
        "fields": {
          "mapping": {
            "type": "short"
          },
          "match_mapping_type": "string",
          "path_match": "status*"
        }
      }
    ]
  }
}
```

This template maps fields starting with "status" to the "short" data type if the initial value is a string.

### Dynamic Mapping Parameters

Dynamic templates support various parameters for matching conditions and mapping rules:

| Parameter | Description |
|-----------|-------------|
| match_mapping_type | Specifies the JSON data type to trigger the mapping |
| match | Regular expression for matching field names |
| unmatch | Regular expression for excluding field names |
| match_pattern | Pattern matching behavior (`regex` or `simple`) |
| path_match | Regular expression for matching nested field paths |
| path_unmatch | Regular expression for excluding nested field paths |
| mapping | The mapping configuration to apply |

## Explicit Mapping

Explicit mapping allows you to define the exact structure and data types for your fields. This approach offers more control and can improve performance.

Example of creating an index with explicit mapping:

```json
PUT sample-index1 
{ 
  "mappings": { 
    "properties": { 
      "year": { 
        "type": "text" 
      }, 
      "age": { 
        "type": "integer" 
      }, 
      "director": { 
        "type": "text" 
      } 
    } 
  } 
}
```

To add mappings to an existing index or data stream:

```json
POST sample-index1/_mapping
{
  "properties": {
    "year": {
      "type": "text"
    },
    "age": {
      "type": "integer"
    },
    "director": {
      "type": "text"
    }
  }
}
```

Important: You cannot change the mapping of an existing field, only modify its parameters.

### Mapping Parameters

Mapping parameters are used to configure the behavior of index fields. For more detailed information on mapping parameters, refer to the OpenSearch documentation on Mappings and field types.

## Mapping Limit Settings

OpenSearch has configurable mapping limits:

| Setting | Default | Range | Type | Description |
|---------|---------|-------|------|-------------|
| index.mapping.nested_fields.limit | 50 | [0,∞) | Dynamic | Max number of nested fields in an index mapping |
| index.mapping.nested_objects.limit | 10,000 | [0,∞) | Dynamic | Max number of nested objects per document |
| index.mapping.total_fields.limit | 1,000 | [0,∞) | Dynamic | Max number of fields in an index mapping |
| index.mapping.depth.limit | 20 | [1,100] | Dynamic | Max depth of nested objects and fields |
| index.mapping.field_name_length.limit | 50,000 | [1,50000] | Dynamic | Max length of field names |
| index.mapper.dynamic | true | {true,false} | Dynamic | Controls dynamic addition of new fields |

## Retrieving Mappings

To get mappings for one or more indexes:

```
GET <index>/_mapping
```

To get all mappings for all indexes:

```
GET _mapping
```

To get mapping for specific fields:

```
GET _mapping/field/<fields>
GET /<index>/_mapping/field/<fields>
```

Example response for retrieving specific field mappings:

```json
{
  "sample-index1": {
    "mappings": {
      "year": {
        "full_name": "year",
        "mapping": {
          "year": {
            "type": "text"
          }
        }
      },
      "age": {
        "full_name": "age",
        "mapping": {
          "age": {
            "type": "integer"
          }
        }
      }
    }
  }
}
```

# OpenSearch Migration and Feature Updates

## Migration Considerations

### Nested JSON Object Limits

When migrating from Elasticsearch OSS 6.8 to OpenSearch 1.x, be aware of the following:

- Documents containing over 10,000 nested JSON objects across all fields will cause migration failures.
- OpenSearch enforces a `index.mapping.nested_objects.limit` setting (default: 10,000) to prevent out-of-memory errors.
- This limit can block shard relocation between Elasticsearch 6.8 and OpenSearch 1.x.

**Recommendation**: Evaluate your data against these limits before initiating migration.

## Version-Specific Changes

### OpenSearch 2.0

1. **API Endpoint Updates**
   - The `type` parameter has been removed from all API endpoints.
   - Indexes can now be categorized by document type.

2. **Terminology Changes**
   - Non-inclusive terms are deprecated and will be removed in OpenSearch 3.0.
   - New terminology:
     - "Allow list" replaces "Whitelist"
     - "Deny list" replaces "Blacklist"
     - "Cluster Manager" replaces "Master"

3. **JDK Support**
   - JDK 8 support has been dropped due to a Lucene upgrade.
   - The Java high-level REST client no longer supports JDK 8.

### OpenSearch 2.5

**Wildcard Query Behavior Change**
- A bug fix affects the `case_insensitive` parameter for wildcard queries on text fields.
- Query results may differ from previous versions.
"""