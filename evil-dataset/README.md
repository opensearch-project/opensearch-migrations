# Evil Dataset

This directory is intended to house the "evil dataset"--a collection of data & associated queries that test edge cases and behavior changes between versions.

## Datapoint Library
The datapoints are contained in `/datapoint-library`. The directory name is the unique identifier for the test case.

Within the datapoint-library directory, the directory structure should be as follows:

```
datapoint-library/
├─ example-datapoint/
│  ├─ README.md		      # human-friendly description of the edgecase or query involved
│  ├─ data.json		      # bulk-api json formatted document with the data to index
│  ├─ query.json	      # Query as an OpenSearch DSL query
│  ├─ expected.json	    # The expected result from the query
│  ├─ expected.7.x.txt	# Optional: the expected result from the query for a specific version
│  ├─ filter.jq         # Optional: a jq filter that pulls out relevant portions of the query response to be compared
├─ second-example-datapoint/
│  ├─ README.md
│  ├─ bulk.json
│  ├─ query.???
│  ├─ expected.txt
│
...
```

## Usage

For the time-being, these datapoints are manually invoked by the user.

The following has an example of how to use the provided files. It depends on an ES/OS cluster running--in this example, locally.

```
> cd example-datapoint

> curl -XPOST 'https://localhost:9200/_bulk?pretty' -ku "admin:admin" -H "Content-Type: application/x-ndjson" --data-binary @data.json
{
  "took": 65,
  "errors": false,
  "items": [ ... ]
}

# The following command shows the full output from the query
> curl -XGET 'https://localhost:9200/_search?pretty' -ku "admin:admin" -H "Content-Type: application/x-ndjson" --data-binary @query.json
{
  "took" : 14,
  "timed_out" : false,
  "_shards" : {
    "total" : 6,
    "successful" : 6,
    "skipped" : 0,
    "failed" : 0
  },
  "hits" : {
    "total" : {
      "value" : 2,
      "relation" : "eq"
    },
    "max_score" : 1.0,
    "hits" : [ ... ]
  }
}

# For ease of comparison, a jq filter can be provided, and this allows for a one-line curl command to compare the actual vs expected output. Any output from this command indicates a mismatch, silence means the query is as expected.
> curl -s -XGET 'https://localhost:9200/_search?pretty' -ku "admin:admin" -H "Content-Type: application/x-ndjson" --data-binary @query.json | jq -f filter.jq | diff - expected.json

# An unsuccesful comparison might look like the following:
> curl -s -XGET 'https://localhost:9200/_search?pretty' -ku "admin:admin" -H "Content-Type: application/x-ndjson" --data-binary @query.json | jq -f filter.jq | diff - expected.json
2c2
<   "count": 4,
---
>   "count": 2,
5,6d4
<     "C",
<     "B",
# Here the query returned 4 hits instead of the expected 2.
```
