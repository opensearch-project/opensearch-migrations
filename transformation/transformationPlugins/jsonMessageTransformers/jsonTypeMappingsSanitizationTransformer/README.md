This transformer converts routes for various requests (see below) to indices that used
[multi-type mappings](https://www.elastic.co/guide/en/elasticsearch/reference/5.6/mapping.html) (configured from ES 5.x 
and earlier clusters) to work with newer versions of Elasticsearch and OpenSearch.

## Usage Prior to Elasticsearch 6 

Let's start with a sample index definition (adapted from the Elasticsearch documentation) with two type mappings and 
documents for each of them.
```
PUT activity
{
  "mappings": {
    "user": {
      "properties": {
        "name": { "type": "text" },
        "user_name": { "type": "keyword" },
        "email": { "type": "keyword" }
      }
    },
    "post": {
      "properties": {
        "content": { "type": "text" },
        "user_name": { "type": "keyword" },
        "post_at": { "type": "date" }
      }
    }
  }
}

PUT activity/user/someuser
{
  "name": "Some User",
  "user_name": "user",
  "email": "user@example.com"
}

PUT activity/post/1
{
  "user_name": "user",
  "tweeted_at": "2024-11-13T00:00:00Z",
  "content": "change is inevitable"
}

GET activity/post/_search
{
  "query": {
    "match": {
      "user_name": "user"
    }
  }
}
```

## Routing data to new indices

The structure of the documents and indices need to change.  Some options are to use separate indices, drop some of 
the types to make an index single-purpose, or to create an index that's the union of all the types' fields.

With a simple mapping directive, we can define each of these three behaviors.  The following yaml shows how to map 
documents into two different indices named users and posts: 
```
activity:
  user: new_users
  post: new_posts
```

To drop one type, just leave it out:
```
activity:
  user: only_users
```

To merge types together, use the same value:
```
activity:
  user: any_activity
  post: any_activity
```

Any _indices_ that are NOT specified won't be modified - all additions, changes, and queries on those other indices not
specified at the root level will remain untouched by the static mapping rewriter.  However, missing types from a 
specified index _**will**_ be removed.  To remove ALL the activity for a given index, specify an empty index with no
children types.
```
activity: {}
```

In addition to static source/target mappings, users can specify source and type pairs as a regex and use any captured 
groups in the target index name.  Regex rules take precedent _after_ the static rules and are only applied when there 
was no index match in the static mappings.  

Regex replacement is controlled via an ordered list of `[indexNamePattern, typeNamePattern, replacementString]`.  
The transformer will use the replacement for the first matched item found.  
If none are found, unlike missing indices for static mappings, the system presumes that the index and type are 
**NOT** to be propagated to the target - any reference to those types and their corresponding data will be suppressed.  
To preserve all items, a default rule will need to be included.  

The following sample shows how indices that start with 'time-' will be migrated and every other index and type not
already matched will be dropped.
```
[
  ["time-(.*)", "(cpu)", "time-$1-$2"]
]
```

The following example preserves all other non-matched items, 
merging all types into a single index with the same name as the source index.
```
[
  ["time-(.*)", "(cpu)", "time-$1-$2"],
  ["(.*)", ".*", "$1"]
]
```

## Final Results

``` 
PUT any_activity
{
    "mappings": {
      "properties": {
        "type": {
          "type": "keyword"
        },
        "name": {
          "type": "text"
        },
        "user_name": {
          "type": "keyword"
        },
        "email": {
          "type": "keyword"
        },
        "content": {
          "type": "text"
        },
        "tweeted_at": {
          "type": "date"
        }
      }
    }
}

PUT any_activity/_doc/someuser
{
  "name": "Some User",
  "user_name": "user",
  "email": "user@example.com"
}


```