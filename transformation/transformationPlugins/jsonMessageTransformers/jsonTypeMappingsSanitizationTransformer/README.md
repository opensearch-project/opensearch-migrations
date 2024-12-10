This transformer converts routes for various requests (see below) to indices that used
[multi-type mappings](https://www.elastic.co/guide/en/elasticsearch/reference/5.6/mapping.html) (configured from ES 5.x 
and earlier clusters) to work with newer versions of Elasticsearch and OpenSearch.  
See "[Removal of Type Mappings](https://www.elastic.co/guide/en/elasticsearch/reference/7.10/removal-of-types.html)"
to understand how type mappings are treated differently through different versions of Elasticsearch.

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

The structure of the documents and indices need to change.  Options are to use separate indices, drop some of 
the types to make an index single-purpose, or to create an index that's the union of all the types' fields.

Specific instances of those behaviors can be expressed via a map (or dictionary) or indices to types to target indices.
The following sample json shows how to map documents from the 'activity' index into two different indices 
('users' and 'posts'): 
```
{
  "activity": {
    "user: "new_users",
    "post": "new_posts"
   }
```

To drop the 'post' type, just leave it out:
```
{
  "activity": {
    "user": "only_users"
  }
}
```

To merge types into a single index, use the same value:
```
{
  "activity": {
    "user": "any_activity",
    "post": "any_activity",
  }
}
```

To remove ALL the activity for a given index, specify an index with no children types.
```
{
  "activity": {}
}
```

Those regex rules take precedence **after** the static mappings specified above.

In addition to static source/target mappings, users can specify source and type pairs as regex patterns and 
use captured groups in the target index name.  
Any source _indices_ that are NOT specified in the maps will be processed through the regex route rules.
The regex rules are only applied if the source index doesn't match a key in the static route map

Regex replace rules are evaluated by concatenating the source index and source types into a single string.
The pattern components are also concatenated into a corresponding match string.
The replacement value will replace the _matched_ part of the source index + typename and replace it with the
specified value.
If that specified value contains (numerical) backreferences, those will pull from the captured groups of the 
concatenated pattern.  
The concatenated pattern is the index pattern followed by the type pattern, meaning that the groups in the index are
numbered from 1 and the type pattern group numbers start after all the groups from the index.

Missing types from a specified index will be removed.
When the regex pattern isn't defined `["(.*)", "(.*)", "\\1_\\2"]` is used to map each type into its own isolated
index, preserving all data and its separation.

For more details about regexes, see the [Python](https://docs.python.org/3/library/re.html#re.sub) or 
[Java](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html) documentation.  
This transform uses python-style backreferences (`'`\1`) for replacement patterns.
Notice that regexes can NOT be specified in the index-type map.
They can _only_ be used via the list, which will be evaluated in the order of the list until a match is found.

The following sample shows how indices that start with 'time-' will be migrated and every other index and type not
already matched will be dropped.
```
[
  ["time-(.*)", "(cpu)", "time-\\1-\\2"]
]
```

The following example preserves all other non-matched items, 
merging all types into a single index with the same name as the source index.
```
[
  ["time-(.*)", "(cpu)", "time-\\1-\\2"],
  ["", ".*", ""]
]
```

For more examples, compare the following cases.  
Though note that anything matched by the static maps shown above will block any of these rules from being evaluated.

| Regex Entry                                                                    | Source Index | Source Type | Target Index      | PUT Doc URL                    | Bulk Index Command                                             |
|--------------------------------------------------------------------------------|-------------|-------------|-------------------|--------------------------------|----------------------------------------------------------------|
| `[["time-(.*)", "(cpu)", "time-\\1-\\2"]]`                                     | time-nov11  | cpu         | time-nov11-cpu    | /time-nov11-cpu/_doc/doc512    | `{"index": {"_index": "time-nov11-cpu", "_id": "doc512" }}`    |
| `[["time-(.*)", "(cpu)", "time-\\1-\\2"]]`                                     | logs        | access      | [DELETED]         | [DELETED]                      | [DELETED]                                                      |
| `[["time-(.*)", "(cpu)", "time-\\1-\\2"],`<br/>` ["(.*)", "(.*)", "\\1-\\2"]]` | logs        | access      | logs_access       | /logs_access/_doc/doc513       | `{"index": {"_index": "logs_access", "_id": "doc513" }}`       |
| `[["time-(.*)", "(cpu)", "time-\\1-\\2"],`<br/>`[["", ".*", ""]]`              | everything  | widgets     | everything        | /everything/_doc/doc514        | `{"index": {"_index": "everything", "_id": "doc514" }}`        |
| `[["time-(.*)", "(cpu)", "time-\\1-\\2"],`<br/>`[["", ".*", ""]]`              | everything  | sprockets   | everything        | /everything/_doc/doc515        | `{"index": {"_index": "everything", "_id": "doc515" }}`        |
| `[["time-(.*)", "(.*)-(cpu)", "\\2-\\3-\\1"]]`                                 | time-nov11  | host123-cpu | host123-cpu-nov11 | /host123-cpu-nov11/_doc/doc512 | `{"index": {"_index": "host123-cpu-nov11", "_id": "doc512" }}` |
| `[["", ".*", ""]]`                                                             | metadata    | users       | metadata          | /metadata/_doc/doc516          | `{"index": {"_index": "metadata", "_id": "doc516" }}`          |
| `[[".*", ".*", "leftovers"]]`                                                  | logs        | access      | leftovers         | /leftovers/_doc/doc517         | `{"index": {"_index": "leftovers", "_id": "doc517" }}`         |
| `[[".*", ".*", "leftovers"]]`                                                  | permissions | access      | leftovers         | /leftovers/_doc/doc517         | `{"index": {"_index": "leftovers", "_id": "doc517" }}`         |

