INDEX_GUIDANCE = """
<multitype_mapping_guidance>
If the source JSON for the index contains multiple mapping types, will create a separate index for each type rather than merging the types into a single typeless mapping.

You will do this by ensuring your transform code returns each new index as a separate dictionary the output list.
</multitype_mapping_guidance>
"""

INDEX_KNOWLEDGE = """
# Elasticsearch Index Creation Guide

## Introduction to Index Creation

Elasticsearch organizes data into indices, which are fundamental structures for storing and retrieving documents. This guide explains how to create an index using the Create Index API, along with important considerations and advanced configuration options.

## Basic Index Creation

To create a new index with default settings, use the following command:

```
PUT <index_name>
```

For example:

```
PUT twitter
```

This creates an index named "twitter" with all default settings.

## Index Naming Rules

When choosing a name for your index, adhere to these restrictions:

- Use only lowercase letters
- Avoid these characters: `\ / * ? " < > | , #` (and space)
- Don't start with `-`, `_`, or `+`
- Avoid `.` or `..` as full names
- Keep names under 255 bytes (multi-byte characters count more)

Note: Prior to Elasticsearch 7.0, colons (`:`) were allowed but are now deprecated.

## Customizing Index Settings

You can specify custom settings when creating an index:

```
PUT <index_name>
{
    "settings": {
        "number_of_shards": 3,
        "number_of_replicas": 2
    }
}
```

Key settings include:
- `number_of_shards`: Determines how the index is split (default: 5)
- `number_of_replicas`: Sets the number of replica shards (default: 1)

For more detailed settings, refer to the index modules documentation.

## Defining Mappings

You can define the structure of your documents using mappings:

```
PUT test
{
    "settings": {
        "number_of_shards": 1
    },
    "mappings": {
        "_doc": {
            "properties": {
                "field1": { "type": "text" }
            }
        }
    }
}
```

## Creating Aliases

Aliases can be created alongside the index:

```json
PUT test
{
    "aliases": {
        "alias_1": {},
        "alias_2": {
            "filter": {
                "term": {"user": "kimchy"}
            },
            "routing": "kimchy"
        }
    }
}
```

## Shard Activation and Response

By default, the API responds once primary shards are active. The response includes:

```json
{
    "acknowledged": true,
    "shards_acknowledged": true,
    "index": "test"
}
```

- `acknowledged`: Indicates successful index creation
- `shards_acknowledged`: Shows if required shard copies started before timeout

You can adjust the number of active shards to wait for:

```json
PUT test
{
    "settings": {
        "index.write.wait_for_active_shards": "2"
    }
}
```

Or use a request parameter:

```
PUT test?wait_for_active_shards=2
```

Note: Even if `acknowledged` or `shards_acknowledged` is `false`, the index creation may still be successful. These values indicate whether the operation completed before the timeout.

## Mapping Without Types (Elasticsearch 7.0+)

From Elasticsearch 7.0, you can create mappings without specifying a type:

```
PUT test?include_type_name=false
{
  "mappings": {
    "properties": {
      "foo": {
        "type": "keyword"
      }
    }
  }
}
```

This approach is recommended for future compatibility as types are being removed from Elasticsearch.

# Elasticsearch Index Aliases

Index aliases in Elasticsearch provide a flexible way to reference one or more indices using a single name. This feature offers several benefits for managing and querying your data.

## Key Concepts

- An alias can point to one or multiple indices
- Aliases automatically resolve to the actual index names in API calls
- An alias cannot share a name with an existing index
- Aliases support filters and routing for advanced use cases

## Basic Alias Operations

### Adding an Alias

To associate the alias `alias1` with index `test1`:

```json
POST /_aliases
{
    "actions" : [
        { "add" : { "index" : "test1", "alias" : "alias1" } }
    ]
}
```

### Removing an Alias

To remove the alias `alias1` from index `test1`:

```json
POST /_aliases
{
    "actions" : [
        { "remove" : { "index" : "test1", "alias" : "alias1" } }
    ]
}
```

### Renaming an Alias

Renaming is achieved through an atomic remove and add operation:

```json
POST /_aliases
{
    "actions" : [
        { "remove" : { "index" : "test1", "alias" : "alias1" } },
        { "add" : { "index" : "test2", "alias" : "alias1" } }
    ]
}
```

### Multiple Index Associations

Assign an alias to multiple indices:

```json
POST /_aliases
{
    "actions" : [
        { "add" : { "indices" : ["test1", "test2"], "alias" : "alias1" } }
    ]
}
```

You can also use glob patterns:

```json
POST /_aliases
{
    "actions" : [
        { "add" : { "index" : "test*", "alias" : "all_test_indices" } }
    ]
}
```

Note: Glob pattern aliases are point-in-time and don't automatically update as matching indices are added or removed.

It is an error to index to an alias which points to more than one index.

### Swapping an Index with an Alias

It's possible to swap an index with an alias in one operation:

```json
PUT test
PUT test_2
POST /_aliases
{
    "actions" : [
        { "add":  { "index": "test_2", "alias": "test" } },
        { "remove_index": { "index": "test" } }
    ]
}
```

The `remove_index` action is equivalent to deleting an index.

## Advanced Alias Features

### Filtered Aliases

Filtered aliases allow you to create different "views" of the same index using Query DSL:

```json
POST /_aliases
{
    "actions" : [
        {
            "add" : {
                 "index" : "test1",
                 "alias" : "alias2",
                 "filter" : { "term" : { "user" : "kimchy" } }
            }
        }
    ]
}
```

Ensure that the fields used in filters exist in the index mapping. For example:

```json
PUT /test1
{
  "mappings": {
    "properties": {
      "user" : {
        "type": "keyword"
      }
    }
  }
}
```

### Routing

Assign routing values to aliases to optimize shard operations:

```json
POST /_aliases
{
    "actions" : [
        {
            "add" : {
                 "index" : "test",
                 "alias" : "alias1",
                 "routing" : "1"
            }
        }
    ]
}
```

You can specify different routing for search and index operations:

```json
POST /_aliases
{
    "actions" : [
        {
            "add" : {
                 "index" : "test",
                 "alias" : "alias2",
                 "search_routing" : "1,2",
                 "index_routing" : "2"
            }
        }
    ]
}
```

Note: Search routing may contain several values separated by comma. Index routing can contain only a single value.

If a search operation that uses routing alias also has a routing parameter, an intersection of both search alias routing and routing specified in the parameter is used. For example:

```
GET /alias2/_search?q=user:kimchy&routing=2,3
```

This command will use "2" as a routing value.

### Write Index

Designate a specific index as the write index for an alias:

```json
POST /_aliases
{
    "actions" : [
        {
            "add" : {
                 "index" : "test",
                 "alias" : "alias1",
                 "is_write_index" : true
            }
        },
        {
            "add" : {
                 "index" : "test2",
                 "alias" : "alias1"
            }
        }
    ]
}
```

This configuration directs write operations to the designated write index when an alias points to multiple indices.

To swap which index is the write index for an alias:

```json
POST /_aliases
{
    "actions" : [
        {
            "add" : {
                 "index" : "test",
                 "alias" : "alias1",
                 "is_write_index" : false
            }
        }, {
            "add" : {
                 "index" : "test2",
                 "alias" : "alias1",
                 "is_write_index" : true
            }
        }
    ]
}
```

Aliases that do not explicitly set `is_write_index: true` for an index, and only reference one index, will have that referenced index behave as if it is the write index until an additional index is referenced. At that point, there will be no write index and writes will be rejected.

## API Endpoints

### Add a Single Alias

```
PUT /{index}/_alias/{name}
```

Parameters:
- `index`: Target index (supports patterns and multiple indices)
- `name`: Alias name (required)
- `routing`: Optional routing value
- `filter`: Optional filter query

You can also use the plural `_aliases`.

### Retrieve Existing Aliases

```
GET /{index}/_alias/{alias}
```

Options:
- `index`: Index name (supports wildcards and multiple indices)
- `alias`: Alias name (supports wildcards and multiple names)
- `ignore_unavailable`: If true, ignore non-existent indices

Examples:

All aliases for the index logs_20162801:
```
GET /logs_20162801/_alias/*
```

All aliases with the name 2016 in any index:
```
GET /_alias/2016
```

All aliases that start with 20 in any index:
```
GET /_alias/20*
```

### Check Alias Existence

```
HEAD /{index}/_alias/{alias}
```

Examples:
```
HEAD /_alias/2016
HEAD /_alias/20*
HEAD /logs_20162801/_alias/*
```

### Delete an Alias

```
DELETE /{index}/_alias/{name}
```

## Aliases During Index Creation

Specify aliases when creating a new index:

```json
PUT /logs_20162801
{
    "mappings" : {
        "properties" : {
            "year" : {"type" : "integer"}
        }
    },
    "aliases" : {
        "current_day" : {},
        "2016" : {
            "filter" : {
                "term" : {"year" : 2016 }
            }
        }
    }
}
```

# Elasticsearch Index Management

## Index Modules and Settings

Index Modules in Elasticsearch are responsible for controlling various aspects of an index. Each index has its own set of modules and associated settings. These settings can be categorized into two types: static and dynamic.

### Static Index Settings

Static settings can only be set during index creation or on a closed index. Changing these settings on a closed index may lead to incorrect configurations that can only be fixed by deleting and recreating the index.

Key static settings include:

1. **Number of Shards** (`index.number_of_shards`)
   - Default: 1024
   - Can be modified using: `export ES_JAVA_OPTS="-Des.index.max_number_of_shards=128"`

2. **Shard Check on Startup** (`index.shard.check_on_startup`)
   - Default: `false`
   - Options: `checksum`, `true`, `fix` (deprecated, same as `false`)
   - Note: This is an expert-level setting and may significantly impact startup time for large indices.

3. **Index Codec** (`index.codec`)
   - Options: `default`, `best_compression`

4. **Routing Partition Size** (`index.routing_partition_size`)

5. **Load Fixed Bitset Filters Eagerly** (`index.load_fixed_bitset_filters_eagerly`)

### Dynamic Index Settings

Dynamic settings can be changed on a live index using the update index settings API. Some key dynamic settings are:

1. **Number of Replicas** (`index.number_of_replicas`)

2. **Auto-expand Replicas** (`index.auto_expand_replicas`)
   - Options: `0-5`, `all`, `0-all`, `false`, `YELLOW`

3. **Refresh Interval** (`index.refresh_interval`)
   - Examples: `1s`, `-1`

4. **Max Result Window** (`index.max_result_window`)
   - Default: 10000
   - Affects `from + size` in search requests

5. **Max Inner Result Window** (`index.max_inner_result_window`)
   - Default: 100

6. **Max Rescore Window** (`index.max_rescore_window`)
   - Defaults to `max(window_size, from + size)` for `rescore`

7. **Read-only Settings**
   - `index.blocks.read_only`: Prevents write operations
   - `index.blocks.read_only_allow_delete`: Similar to read-only, but allows index deletion
   - `index.blocks.read`: Disables read operations
   - `index.blocks.write`: Disables write operations
   - `index.blocks.metadata`: Disables metadata operations

8. **Shard Allocation and Rebalancing**
   - `index.routing.allocation.enable`: Controls shard allocation
     - Options: `all` (default), `primaries`, `new_primaries`, `none`
   - `index.routing.rebalance.enable`: Enables shard rebalancing
     - Options: `all` (default), `primaries`, `replicas`, `none`

9. **Other Notable Settings**
   - `index.max_docvalue_fields_search`: Limits `docvalue_fields` in search requests
   - `index.max_script_fields`: Limits `script_fields` in search requests (default: 32)
   - `index.max_ngram_diff`: Sets maximum ngram difference (default: 1)
   - `index.max_shingle_diff`: Sets maximum shingle difference (default: 3)
   - `index.max_refresh_listeners`: Limits concurrent refresh listeners
   - `index.highlight.max_analyzed_offset`: Sets maximum number of characters analyzed for highlighting
   - `index.max_terms_count`: Maximum number of terms in a terms query (default: 65536)
   - `index.gc_deletes`: Sets the duration for retaining deleted documents (default: 60s)
   - `index.max_regex_length`: Sets maximum length of regex in a regexp query (default: 1000)
   - `index.default_pipeline`: Sets the default ingest pipeline for the index

## Additional Index Modules

Elasticsearch provides various other index modules with their own specific settings. These modules cater to different aspects of index management and functionality. For more detailed information on these modules, refer to the Elasticsearch documentation.

## Best Practices

1. Exercise caution when modifying static settings, especially on production indices.
2. Regularly review and optimize dynamic settings based on your use case and performance requirements.
3. Be aware of the implications of enabling read-only modes and their impact on write operations and index management.
4. When using advanced features like custom routing or sharding, ensure you understand their effects on index performance and scalability.

# Understanding Text Analysis in Elasticsearch

## Introduction to Analysis

Text analysis is a crucial process in Elasticsearch that transforms raw text into searchable tokens. This process occurs during both indexing and searching, ensuring efficient and accurate retrieval of information.

## The Analysis Process

Analysis involves breaking down text into individual terms, which are then added to an inverted index for quick searching. This process is handled by analyzers, which can be either built-in or custom-defined for each index.

### Example of Analysis

Consider the following sentence:

```
"The QUICK brown foxes jumped over the lazy dog!"
```

When processed by the built-in English analyzer, it undergoes several transformations:

1. Tokenization: Breaks the text into individual words
2. Lowercasing: Converts all tokens to lowercase
3. Stopword removal: Eliminates common words like "the"
4. Stemming: Reduces words to their root form (e.g., "foxes" to "fox")

The resulting tokens added to the inverted index would be:

```
[quick, brown, fox, jump, over, lazi, dog]
```

## Configuring Analysis

### Index-Time Analysis

You can specify an analyzer for each text field in your mapping:

```json
PUT my_index
{
  "mappings": {
    "_doc": {
      "properties": {
        "title": {
          "type": "text",
          "analyzer": "standard"
        }
      }
    }
  }
}
```

If no analyzer is specified, Elasticsearch looks for a `default` analyzer in the index settings. If none is found, it uses the `standard` analyzer.

### Search-Time Analysis

The same analysis process applies to search queries, ensuring that the query terms match the indexed terms. For example, searching for "a quick fox" would be analyzed into:

```
[quick, fox]
```

This allows for matching even when the exact words differ (e.g., "quick" vs. "QUICK", "fox" vs. "foxes").

### Determining the Search Analyzer

Elasticsearch determines which analyzer to use for searching in the following order:

1. Analyzer specified in the query itself
2. `search_analyzer` mapping parameter
3. `analyzer` mapping parameter
4. `default_search` analyzer in index settings
5. `default` analyzer in index settings
6. `standard` analyzer

## Best Practices

- Use the same analyzer at index and search time for consistency
- Choose analyzers based on the language and specific requirements of your data
- Consider custom analyzers for specialized text processing needs

# Customizing Shard Allocation in Elasticsearch

Elasticsearch provides powerful mechanisms to control where shards of an index are allocated within your cluster. This feature, known as shard allocation filtering, allows you to fine-tune the distribution of your data across nodes based on various criteria.

## Node Attributes: The Building Blocks

Before diving into allocation rules, it's crucial to understand node attributes. These are custom metadata tags you can assign to your Elasticsearch nodes, providing a flexible way to categorize them. For example:

```bash
bin/elasticsearch -Enode.attr.rack=rack1 -Enode.attr.size=big
```

You can set these attributes either via command line arguments or in the `elasticsearch.yml` configuration file.

## Crafting Allocation Rules

With node attributes in place, you can create allocation rules using the `index.routing.allocation.*` settings. These settings come in three flavors:

1. `include`: Allows shards on nodes with matching attributes
2. `exclude`: Prevents shards from being allocated to nodes with matching attributes
3. `require`: Mandates that shards must be on nodes with the specified attributes

### Examples in Action

Let's explore some practical scenarios:

1. Allocate to specific node types:

```json
PUT test/_settings
{
  "index.routing.allocation.include.size": "big,medium"
}
```

This allocates shards of `test` index to nodes tagged as either `big` or `medium`.

2. Avoid certain nodes:

```json
PUT test/_settings
{
  "index.routing.allocation.exclude.size": "small"
}
```

This prevents `test` index shards from being allocated to nodes tagged as `small`.

3. Strict allocation requirements:

```json
PUT test/_settings
{
  "index.routing.allocation.include.size": "big",
  "index.routing.allocation.include.rack": "rack1"
}
```

This ensures `test` index shards are only on `big` nodes in `rack1`.

## Beyond Custom Attributes

Elasticsearch also provides built-in attributes for even more granular control:

| Attribute | Description |
|-----------|-------------|
| `_name` | Target specific nodes by name |
| `_host_ip` | Match nodes by their host IP address (IP associated with hostname) |
| `_publish_ip` | Filter based on the node's publish IP address |
| `_ip` | Matches either `_host_ip` or `_publish_ip` |
| `_host` | Select nodes by hostname |

These can be used just like custom attributes in your allocation rules.

### Wildcard Magic

For added flexibility, attribute values support wildcards. For instance:

```json
PUT test/_settings
{
  "index.routing.allocation.include._ip": "192.168.2.*"
}
```

This targets all nodes with IP addresses in the 192.168.2.0/24 subnet.

## Key Considerations

- Multiple rules combine with AND logic – all conditions must be satisfied.
- If no suitable nodes match your criteria, shard movement will not occur.
- These settings are dynamic, allowing you to adjust allocation rules on live indices.
- The per-index shard allocation filters work in conjunction with the cluster-wide allocation filters explained in Cluster Level Shard Allocation.

# Optimizing Node Departure Handling in Elasticsearch

When a node unexpectedly leaves an Elasticsearch cluster, the system's default response can sometimes lead to unnecessary strain. This document outlines the process, potential issues, and how to optimize cluster behavior in such scenarios.

## Default Cluster Reaction

Upon detecting a node's departure, Elasticsearch typically:

1. Elevates replica shards to primary status to replace lost primaries
2. Allocates new replicas to maintain redundancy (if sufficient nodes are available)
3. Redistributes shards for optimal balance across remaining nodes

While this approach prioritizes data integrity by ensuring every shard is fully replicated as soon as possible, it can inadvertently cause significant cluster load, especially if the node's absence is temporary.

## The "Shard Shuffle" Problem

Consider this scenario:

1. Node 5 experiences a brief network disconnection
2. Cluster promotes replicas and allocates new ones
3. Substantial data is copied across the network
4. Cluster undergoes rebalancing
5. Node 5 reconnects shortly after
6. Another rebalancing occurs to incorporate Node 5

This sequence can lead to unnecessary data transfer and processing, particularly if Node 5's absence was brief. The process would be even quicker for idle shards (those not receiving indexing requests) which have been automatically sync-flushed.

## Delayed Allocation: A Smarter Approach

Elasticsearch offers a solution: delayed allocation of unassigned replica shards. This feature is controlled by the `index.unassigned.node_left.delayed_timeout` setting, which defaults to `1m` (one minute).

### Configuring Delayed Allocation

To modify this setting cluster-wide or for a specific index:

```json
PUT _all/_settings
{
  "settings": {
    "index.unassigned.node_left.delayed_timeout": "5m"
  }
}
```

This example sets a 5-minute delay before reallocating shards from a departed node.

### Revised Scenario with Delayed Allocation

With this feature enabled, the process changes:

1. Node 5 loses connectivity
2. Primaries are still immediately replaced
3. Cluster logs a delay message for unassigned shard allocation
4. Cluster status remains yellow due to unassigned replicas
5. If Node 5 returns before timeout expiration, replicas are quickly reinstated

Note: This delay doesn't affect primary shard promotion or allocation of previously unassigned replicas. It also resets after a full cluster restart or master node failover.

## Shard Relocation Cancellation

If the delay timeout is exceeded and reallocation begins, but the original node rejoins with matching sync IDs, Elasticsearch will cancel the ongoing relocation in favor of the returning node's data. This is why the default timeout is set to just one minute: even if shard relocation begins, cancelling recovery in favor of the synced shard is relatively inexpensive.

## Monitoring Delayed Allocations

To check the number of shards affected by delayed allocation:

```
GET _cluster/health
```

Look for the `delayed_unassigned_shards` value in the response.

## Handling Permanent Node Loss

If a node won't be returning, you can trigger immediate shard allocation:

```json
PUT _all/_settings
{
  "settings": {
    "index.unassigned.node_left.delayed_timeout": "0"
  }
}
```

This setting can be reverted once recovery begins.

# Controlling Shard Distribution in Elasticsearch

Elasticsearch's cluster-level shard allocator aims to distribute shards from a single index across multiple nodes. However, achieving perfect balance isn't always feasible, especially when dealing with varying numbers of shards, indices, and their respective sizes.

## Index-Specific Shard Limit

To manage shard distribution more precisely, Elasticsearch offers a dynamic setting that enforces a maximum number of shards from a single index on any given node:

```
index.routing.allocation.total_shards_per_node
```

This setting allows you to fine-tune shard allocation on a per-index basis.

## Global Shard Limit

For a broader approach to shard management, you can set a cluster-wide limit on the number of shards per node, regardless of which index they belong to:

```
cluster.routing.allocation.total_shards_per_node
```

This global setting helps maintain overall cluster balance.

## Important Considerations

Both of these configurations impose strict limits on shard allocation. As a result, some shards may remain unallocated if the specified limits are reached. It's crucial to use these settings with caution, as they can significantly impact your cluster's performance and data distribution.

## Additional Resources

For those new to Elasticsearch and the ELK stack, consider exploring these popular introductory videos:

1. Video: Get Started with Elasticsearch
2. Video: Intro to Kibana
3. Video: ELK for Logs & Metrics

These resources provide valuable insights into the fundamentals of the Elasticsearch ecosystem and its applications in log and metric analysis.

# Understanding Elasticsearch Mappings

## Introduction to Mappings

Mappings in Elasticsearch define how documents and their fields are stored and indexed. This crucial process determines the searchability and analysis of your data. Key aspects of mapping include:

- Designating full-text fields
- Specifying field types (e.g., numbers, dates, geolocations)
- Configuring the catch-all `_all` field indexing
- Setting date value formats
- Establishing rules for dynamically added fields

## Mapping Types and Their Evolution

Historically, each index could have multiple mapping types. However, this feature has been deprecated since version 6.0.0. Currently:

- Every index has a single mapping type
- This type defines the document's indexing structure
- Standard mapping type components include:
  - `_index`
  - `_type`
  - `_id`
  - `_source`
  - `properties`

For more information on this change, refer to the "Removal of mapping types" documentation.

## Field Data Types

Elasticsearch supports a variety of field data types to accommodate different kinds of information:

1. Simple types:
   - `text`, `keyword`, `date`, `long`, `double`, `boolean`, `ip`
2. Hierarchical types (for JSON-like structures):
   - `object`, `nested`
3. Specialized types:
   - `geo_point`, `geo_shape`, `completion`

### Multi-fields

Fields can be indexed in multiple ways to serve different purposes. For example:
- A string field could be indexed as both `text` (for full-text search) and `keyword` (for sorting and aggregations)
- Text can be analyzed using different analyzers (e.g., `standard`, `english`, `french`)

This flexibility is achieved through the `fields` parameter, supported by most data types.

## Preventing Mapping Explosion

To avoid potential out-of-memory errors caused by an excessive number of field mappings (mapping explosion), Elasticsearch provides several limiting settings:

1. `index.mapping.total_fields.limit`: Caps the total number of fields (default: 1000)
2. `index.mapping.depth.limit`: Restricts the depth of nested objects (default: 20)
3. `index.mapping.nested_fields.limit`: Limits the number of `nested` fields (default: 50)

These safeguards are particularly important when using dynamic mappings, where new fields are automatically added as documents are indexed.

## Dynamic vs. Explicit Mappings

### Dynamic Mapping

Elasticsearch can automatically detect and map new fields as they're indexed. This feature:
- Allows for flexibility in document structure
- Works for top-level fields and nested objects
- Can be customized to fit specific needs

### Explicit Mapping

While dynamic mapping is convenient, explicit mapping offers more control:
- Allows you to define exact field types and properties
- Can be set during index creation or updated using the PUT mapping API
- Provides better optimization for your specific use case

Note: Existing field mappings generally cannot be updated. To change a field's mapping, you typically need to create a new index and reindex your data. If you only wish to rename a field without changing its mapping, consider using an `alias` field.

## Example: Creating an Index with Explicit Mapping

```json
PUT my_index
{
  "mappings": {
    "_doc": {
      "properties": {
        "title":    { "type": "text" },
        "name":     { "type": "text" },
        "age":      { "type": "integer" },
        "created":  {
          "type":   "date",
          "format": "strict_date_optional_time||epoch_millis"
        }
      }
    }
  }
}
```

This example demonstrates:
1. Creating an index named `my_index`
2. Defining a mapping type `_doc`
3. Specifying properties for fields:
   - `title` and `name` as text fields
   - `age` as an integer
   - `created` as a date field with two possible formats

# Understanding Elasticsearch Shards and Merging

## Shard Structure
Elasticsearch shards are composed of Lucene indexes, which are further divided into segments. These segments serve as the fundamental storage units within the index, containing the actual index data. One key characteristic of segments is their immutability.

## Segment Merging
To maintain optimal index size and remove deleted entries, Elasticsearch periodically combines smaller segments into larger ones. This process, known as merging, employs auto-throttling to balance resource utilization between merging operations and other critical tasks like search functionality.

## Merge Scheduler

The Concurrent Merge Scheduler oversees merge operations, executing them as needed. Key points about the merge scheduler include:

1. Merge operations run in dedicated threads.
2. When the maximum thread count is reached, subsequent merges are queued.
3. A dynamic setting controls the maximum thread count:

   ```
   index.merge.scheduler.max_thread_count
   ```

4. The default maximum thread count is calculated using the following formula:

   ```java
   Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2))
   ```

   This ensures at least 1 thread and at most 4 threads, depending on the available processors.

# Elasticsearch Similarity Module

The Similarity module in Elasticsearch defines how matching documents are scored and ranked. This feature operates on a per-field basis, allowing different similarity algorithms to be applied to different fields through mapping configurations.

## Configuring Custom Similarities

While the built-in similarities are generally sufficient for most use cases, advanced users can configure custom similarities. These configurations are set via index settings when creating or updating an index.

Example configuration:

```json
PUT /index
{
    "settings": {
        "index": {
            "similarity": {
                "my_similarity": {
                    "type": "DFR",
                    "basic_model": "g",
                    "after_effect": "l",
                    "normalization": "h2",
                    "normalization.h2.c": "3.0"
                }
            }
        }
    }
}
```

To apply the custom similarity to a specific field:

```json
PUT /index/_mapping/_doc
{
  "properties": {
    "title": { "type": "text", "similarity": "my_similarity" }
  }
}
```

## Available Similarity Algorithms

### 1. BM25 Similarity (Default)

- Type name: `BM25`
- Based on TF/IDF
- Suitable for short fields like names
- Options:
  - `k1`: Controls non-linear term frequency normalization (default: 1.2)
  - `b`: Controls document length normalization of tf values (default: 0.75)
  - `discount_overlaps`: Ignores overlap tokens when computing norms (default: true)

### 2. Classic Similarity

- Type name: `classic`
- Deprecated since version 6.3.0
- Based on TF/IDF model
- Option: `discount_overlaps`

### 3. DFR Similarity

- Type name: `DFR`
- Implements the divergence from randomness framework
- Options:
  - `basic_model`: be, d, g, if, in, ine, p
  - `after_effect`: no, b, l
  - `normalization`: no, h1, h2, h3, z

### 4. DFI Similarity

- Type name: `DFI`
- Implements the divergence from independence model
- Option: `independence_measure` (values: standardized, saturated, chisquared)

### 5. IB Similarity

- Type name: `IB`
- Information-based model
- Options:
  - `distribution`: ll, spl
  - `lambda`: df, ttf
  - `normalization`: Same as DFR similarity

### 6. LM Dirichlet Similarity

- Type name: `LMDirichlet`
- Option: `mu` (default: 2000)

### 7. LM Jelinek Mercer Similarity

- Type name: `LMJelinekMercer`
- Option: `lambda` (default: 0.1, optimal: 0.1 for title queries, 0.7 for long queries)

### 8. Scripted Similarity

- Type name: `scripted`
- Allows custom score computation using scripts
- Components:
  - `script`: Computes the score
  - `weight_script`: Optional, computes document-independent part of the score

Example of a scripted TF-IDF implementation:

```json
PUT /index
{
  "settings": {
    "number_of_shards": 1,
    "similarity": {
      "scripted_tfidf": {
        "type": "scripted",
        "script": {
          "source": "double tf = Math.sqrt(doc.freq); double idf = Math.log((field.docCount+1.0)/(term.docFreq+1.0)) + 1.0; double norm = 1/Math.sqrt(doc.length); return query.boost * tf * idf * norm;"
        }
      }
    }
  },
  "mappings": {
    "_doc": {
      "properties": {
        "field": {
          "type": "text",
          "similarity": "scripted_tfidf"
        }
      }
    }
  }
}
```

Note: Scripted similarities must follow specific rules to ensure correct functionality:
- Returned scores must be positive.
- All other variables remaining equal, scores must not decrease when `doc.freq` increases.
- All other variables remaining equal, scores must not increase when `doc.length` increases.

For improved efficiency, you can use a `weight_script` to compute the document-independent part of the score:

```json
PUT /index
{
  "settings": {
    "number_of_shards": 1,
    "similarity": {
      "scripted_tfidf": {
        "type": "scripted",
        "weight_script": {
          "source": "double idf = Math.log((field.docCount+1.0)/(term.docFreq+1.0)) + 1.0; return query.boost * idf;"
        },
        "script": {
          "source": "double tf = Math.sqrt(doc.freq); double norm = 1/Math.sqrt(doc.length); return weight * tf * norm;"
        }
      }
    }
  },
  "mappings": {
    "_doc": {
      "properties": {
        "field": {
          "type": "text",
          "similarity": "scripted_tfidf"
        }
      }
    }
  }
}
```

## Changing the Default Similarity

To change the default similarity for all fields in an index:

1. When creating the index:

```json
PUT /index
{
  "settings": {
    "index": {
      "similarity": {
        "default": {
          "type": "boolean"
        }
      }
    }
  }
}
```

2. For an existing index:

```json
POST /index/_close

PUT /index/_settings
{
  "index": {
    "similarity": {
      "default": {
        "type": "boolean"
      }
    }
  }
}

POST /index/_open
```

# Elasticsearch Slow Logs

Elasticsearch provides two types of slow logs to help monitor and optimize performance: Search Slow Log and Index Slow Log. These logs allow you to track queries, fetch operations, and indexing operations that exceed specified time thresholds.

## Search Slow Log

The Search Slow Log records slow search operations at the shard level, including both query and fetch phases.

### Configuration

You can set thresholds for different logging levels (warn, info, debug, trace) for both query and fetch phases. Here's an example configuration:

```yaml
index.search.slowlog.threshold.query.warn: 10s
index.search.slowlog.threshold.query.info: 5s
index.search.slowlog.threshold.query.debug: 2s
index.search.slowlog.threshold.query.trace: 500ms

index.search.slowlog.threshold.fetch.warn: 1s
index.search.slowlog.threshold.fetch.info: 800ms
index.search.slowlog.threshold.fetch.debug: 500ms
index.search.slowlog.threshold.fetch.trace: 200ms

index.search.slowlog.level: info
```

These settings are dynamic and can be updated for each index using the Update Indices Settings API:

```json
PUT /twitter/_settings
{
    "index.search.slowlog.threshold.query.warn": "10s",
    "index.search.slowlog.threshold.query.info": "5s",
    "index.search.slowlog.threshold.query.debug": "2s",
    "index.search.slowlog.threshold.query.trace": "500ms",
    "index.search.slowlog.threshold.fetch.warn": "1s",
    "index.search.slowlog.threshold.fetch.info": "800ms",
    "index.search.slowlog.threshold.fetch.debug": "500ms",
    "index.search.slowlog.threshold.fetch.trace": "200ms",
    "index.search.slowlog.level": "info"
}
```

### Important Notes

- By default, all thresholds are disabled (set to `-1`).
- Not all levels need to be configured; you can set only the ones you need.
- Logging occurs at the shard level, providing insights into execution on specific machines.

### Log File Configuration

The Search Slow Log file is configured in `log4j2.properties`. Here's the default configuration:

```properties
appender.index_search_slowlog_rolling.type = RollingFile
appender.index_search_slowlog_rolling.name = index_search_slowlog_rolling
appender.index_search_slowlog_rolling.fileName = ${sys:es.logs}_index_search_slowlog.log
appender.index_search_slowlog_rolling.layout.type = PatternLayout
appender.index_search_slowlog_rolling.layout.pattern = [%d{ISO8601}][%-5p][%-25c] [%node_name]%marker %.10000m%n
appender.index_search_slowlog_rolling.filePattern = ${sys:es.logs}_index_search_slowlog-%d{yyyy-MM-dd}.log
appender.index_search_slowlog_rolling.policies.type = Policies
appender.index_search_slowlog_rolling.policies.time.type = TimeBasedTriggeringPolicy
appender.index_search_slowlog_rolling.policies.time.interval = 1
appender.index_search_slowlog_rolling.policies.time.modulate = true

logger.index_search_slowlog_rolling.name = index.search.slowlog
logger.index_search_slowlog_rolling.level = trace
logger.index_search_slowlog_rolling.appenderRef.index_search_slowlog_rolling.ref = index_search_slowlog_rolling
logger.index_search_slowlog_rolling.additivity = false
```

## Index Slow Log

The Index Slow Log is similar to the Search Slow Log but focuses on indexing operations.

### Configuration

You can set thresholds for different logging levels for indexing operations. Here's an example configuration:

```yaml
index.indexing.slowlog.threshold.index.warn: 10s
index.indexing.slowlog.threshold.index.info: 5s
index.indexing.slowlog.threshold.index.debug: 2s
index.indexing.slowlog.threshold.index.trace: 500ms
index.indexing.slowlog.level: info
index.indexing.slowlog.source: 1000
```

These settings can be updated dynamically using the Update Indices Settings API:

```json
PUT /twitter/_settings
{
    "index.indexing.slowlog.threshold.index.warn": "10s",
    "index.indexing.slowlog.threshold.index.info": "5s",
    "index.indexing.slowlog.threshold.index.debug": "2s",
    "index.indexing.slowlog.threshold.index.trace": "500ms",
    "index.indexing.slowlog.level": "info",
    "index.indexing.slowlog.source": "1000"
}
```

### Additional Settings

- `index.indexing.slowlog.source`: Controls how much of the `_source` field is logged (default: 1000 characters).
  - Set to `false` or `0` to skip logging the source entirely.
  - Set to `true` to log the entire source regardless of size.
- `index.indexing.slowlog.reformat`: Controls whether the source is reformatted (default: true).
  - Set to `false` to log the source "as is", potentially spanning multiple log lines.

### Log File Configuration

The Index Slow Log file is configured in `log4j2.properties`. Here's the default configuration:

```properties
appender.index_indexing_slowlog_rolling.type = RollingFile
appender.index_indexing_slowlog_rolling.name = index_indexing_slowlog_rolling
appender.index_indexing_slowlog_rolling.fileName = ${sys:es.logs}_index_indexing_slowlog.log
appender.index_indexing_slowlog_rolling.layout.type = PatternLayout
appender.index_indexing_slowlog_rolling.layout.pattern = [%d{ISO8601}][%-5p][%-25c] [%node_name]%marker %.-10000m%n
appender.index_indexing_slowlog_rolling.filePattern = ${sys:es.logs}_index_indexing_slowlog-%d{yyyy-MM-dd}.log
appender.index_indexing_slowlog_rolling.policies.type = Policies
appender.index_indexing_slowlog_rolling.policies.time.type = TimeBasedTriggeringPolicy
appender.index_indexing_slowlog_rolling.policies.time.interval = 1
appender.index_indexing_slowlog_rolling.policies.time.modulate = true

logger.index_indexing_slowlog.name = index.indexing.slowlog.index
logger.index_indexing_slowlog.level = trace
logger.index_indexing_slowlog.appenderRef.index_indexing_slowlog_rolling.ref = index_indexing_slowlog_rolling
logger.index_indexing_slowlog.additivity = false
```

# Elasticsearch Storage Configuration

## Overview

Elasticsearch provides flexible options for controlling how index data is stored and accessed on disk. This document outlines the various storage types available and how to configure them.

## File System Storage Types

Elasticsearch offers multiple file system implementations, known as storage types. By default, it selects the most suitable implementation based on the operating environment.

### Configuring Storage Types

#### Global Configuration

To set a storage type for all indices, add the following to your `config/elasticsearch.yml` file:

```yaml
index.store.type: niofs
```

#### Per-Index Configuration

You can also specify the storage type for individual indices at creation time:

```json
PUT /my_index
{
  "settings": {
    "index.store.type": "niofs"
  }
}
```

**Note**: Configuring storage types is considered an expert-level setting and may be subject to change in future releases.

### Available Storage Types

Elasticsearch supports the following storage types:

1. `fs`
2. `mmapfs`
3. `simplefs`
4. `SimpleFsDirectory`
5. `niofs`
6. `NIOFSDirectory`
7. `MMapDirectory`
8. `hybridfs`

### Memory-Mapped Storage Restrictions

The use of `mmapfs` and the related `hybridfs` storage types can be controlled using the `node.store.allow_mmap` setting. This boolean setting determines whether memory-mapping is allowed, with the default being `true`.

To disable memory-mapping:

```yaml
node.store.allow_mmap: false
```

This setting is particularly useful in environments where you cannot control the creation of memory maps and need to disable memory-mapping capabilities.

# Understanding Translog in Elasticsearch

## Introduction to Translog

In Elasticsearch, a translog is a crucial component that ensures data durability and aids in recovery processes. It addresses the gap between Lucene commits, which are resource-intensive operations that persist changes to disk.

## Purpose and Functionality

1. **Data Persistence**: The translog records all index and delete operations that occur between Lucene commits.
2. **Recovery Mechanism**: In case of unexpected shutdowns or hardware failures, the translog allows Elasticsearch to recover recent, acknowledged transactions that haven't been included in the last Lucene commit.

## Flush Operation

A flush in Elasticsearch involves two main actions:
1. Performing a Lucene commit
2. Starting a new translog

Flushes occur automatically to prevent the translog from growing excessively large, which could lead to prolonged recovery times. While manual flushing is possible via API, it's rarely necessary.

## Translog Settings

Translog behavior can be fine-tuned using several dynamically updatable per-index settings:

- `index.translog.sync_interval`: Defaults to 5s (minimum: 100ms)
- `index.translog.durability`: 
  - `async`: Fsync and commit every 5 seconds
  - `request` (default): Fsync and commit after every index, delete, update, or bulk request
- `index.translog.flush_threshold_size`: Defaults to 512mb
- `index.translog.retention.size`
- `index.translog.retention.age`: Defaults to 12h

## Handling Translog Corruption

In rare cases of translog corruption, Elasticsearch provides a tool for recovery:

```bash
bin/elasticsearch-translog truncate -d /path/to/translog/directory
```

Important notes:
- This tool is deprecated and will be removed in Elasticsearch 7.0
- Use `elasticsearch-shard` tool instead
- Stop Elasticsearch before running this tool
- Running this tool will result in data loss from the translog

The tool will display warnings and ask for confirmation before proceeding. It will then remove existing translog files and create a new empty checkpoint and translog.
"""