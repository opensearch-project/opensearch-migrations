## Deprecation of the string mapping type

In Elasticsearch 5 (ES5) the mapping type for fields had breaking changes made to them, notably the 'string' type was replaced with 'text'.  This is of importance since it impacts every cluster running Elasticsearch 2 (ES2).  Below is how these different type mappings look, this is a straight forward conversion on its face, 'string' -> 'text'.

#### ES2 type mapping
```json
"mappings": {
    "properties": {
        "field1": {
           "type": "string"
        }
    }
}
```

#### ES5 type mapping
```json
"mappings": {
    "properties": {
        "field1": {
           "type": "text"
        }
    }
}
```

However, there is a significant functional difference between these two types, aggregation logic cannot be performed on a 'text' type field.  To accommodate filtering an additional field is created of type 'keyword', by default in all ES/OS versions these fields are added automatically.  No modification of the source documented is needed, the 'keyword' field is automatically populated.

#### ES5 default type mapping
```json
"mappings": {
   "properties": {
        "field1": {
            "type": "text",
            "fields": {
                "keyword": {
                    "type": "keyword",
                    "ignore_above": 256
                }
            }
       }
    }
}
```

In ES5 text and keyword fields are used for different purposes.

Text fields are passed through analyzers where it is full text searchable. These can be used for approximant matches and search scoring use the 'text' field.

Keyword fields are truncated and treated as a single opaque token supporting only exact matches. Precise filtering and sorting use the 'keyword' field supporting aggregation usage.

Lets consider a search request in ES2 to count all the fields with matching names.

```bash
curl -s localhost:9200/myindex/_search -H "Content-Type: application/json" -d '{
    "aggs": {
        "count_fields" : { "value_count" : { "field" : "a" } }
    }
}'| jq
```

In ES5 this query returns an error `Fielddata is disabled on text fields by default. Set fielddata=true on [a] in order to load fielddata in memory by uninverting the inverted index. Note that this can however use significant memory. Alternatively use a keyword field instead.`, the tweak recommended by the error message, assuming the keyword field is enable, is as follows:

```bash
curl -s localhost:9200/myindex/_search -H "Content-Type: application/json" -d '{
    "aggs": {
        "count_fields" : { "value_count" : { "field" : "a.keyword" } }
    }
}'| jq
```

Customers migration from ES2 -> ES5 or greater will need to handle this different and the implications on queries in there applications use case accordingly.

### See the difference

Start an ES2 cluster for testing
```bash
docker run -it --rm --name es24 -e "discovery.type=single-node" -e "ES_JAVA_OPTS=-Xms512m -Xmx512m" -p 9200:9200 -p 9300:9300 elasticsearch:2.4.6
```

Start an ES5 cluster for testing
```bash
docker run -it --rm --name es56 -e "discovery.type=single-node" -e "ES_JAVA_OPTS=-Xms512m -Xmx512m" -p 9200:9200 -p 9300:9300 elasticsearch:5.6.16
```

#### Create document - ES2 & ES5
```bash
curl -s -XPOST localhost:9200/myindex/doc -d '{"a": "abc"}'| jq
```

#### Aggregate Query - ES2
```bash
curl -s localhost:9200/myindex/_search -H "Content-Type: application/json" -d '{
    "aggs": {
        "count_fields" : { "value_count" : { "field" : "a" } }
    }
}'| jq
```

#### Aggregate Query Keyword - ES5
```bash
curl -s localhost:9200/myindex/_search -H "Content-Type: application/json" -d '{
    "aggs": {
        "count_fields" : { "value_count" : { "field" : "a.keyword" } }
    }
}'| jq
```
