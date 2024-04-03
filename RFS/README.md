# reindex-from-snapshot

## How to run

### Set up your ES 6.8 Source Cluster

```
git clone git@github.com:elastic/elasticsearch.git
git checkout 6.8
```

Open up IntelliJ and load the root directory.  Then follow [these instructions](https://www.elastic.co/blog/how-to-debug-elasticsearch-source-code-in-intellij-idea) to run Elasticsearch from source w/ the debugger attached.

Then, you can run some commands to set up some test indices/snapshot:

```
curl -u "elastic-admin:elastic-password" -X PUT "localhost:9200/_template/posts_index_template" -H "Content-Type: application/json" -d'
{
    "index_patterns": ["posts_*"],
    "settings": {
        "number_of_shards": 1,
        "number_of_replicas": 1
    },
    "mappings": {
        "_doc": {
            "properties": {
                "contents": {"type": "text"},
                "author": {"type": "keyword"},
                "tags": {"type": "keyword"}
            }
        }
    },
    "aliases": {
        "alias1": {},
        "alias2": {
            "filter": {
                "term": {"user": "kimchy"}
            },
            "routing": "kimchy"
        },
        "{index}-alias": {}
    }
}'

curl -u "elastic-admin:elastic-password" -X PUT "localhost:9200/posts_2023_02_25"

curl -u "elastic-admin:elastic-password" -X POST "localhost:9200/posts_2023_02_25/_doc" -H "Content-Type: application/json" -d'
{
  "contents": "This is a sample blog post content.",
  "author": "Author Name",
  "tags": ["Elasticsearch", "Tutorial"]
}'

curl -u "elastic-admin:elastic-password" -X PUT "localhost:9200/posts_2024_01_01" -H "Content-Type: application/json" -d'
{
  "aliases": {
    "another_alias": {
      "routing": "user123",
      "filter": {
        "term": {
          "author": "Tobias Funke"
        }
      }
    }
  }
}'

curl -u "elastic-admin:elastic-password" -X POST "localhost:9200/another_alias/_doc" -H "Content-Type: application/json" -d'
{
  "contents": "How Elasticsearch helped my patients",
  "author": "Tobias Funke",
  "tags": ["Elasticsearch", "Tutorial"]
}'

curl -u "elastic-admin:elastic-password" -X POST "localhost:9200/another_alias/_doc" -H "Content-Type: application/json" -d'
{
  "contents": "My Time in the Blue Man Group",
  "author": "Tobias Funke",
  "tags": ["Lifestyle"]
}'

curl -X PUT "localhost:9200/_snapshot/fs_repository" -u "elastic-admin:elastic-password" -H "Content-Type: application/json" -d'
{
  "type": "fs",
  "settings": {
    "location": "/Users/chelma/workspace/ElasticSearch/elasticsearch/distribution/build/cluster/shared/repo"
  }
}'

curl -X PUT "localhost:9200/_snapshot/fs_repository/global_state_snapshot?wait_for_completion=true" -u "elastic-admin:elastic-password"  -H "Content-Type: application/json" -d'
{
  "indices": ["posts_2023_02_25", "posts_2024_01_01"],
  "ignore_unavailable": true,
  "include_global_state": true
}'
``` 

### Set up your OS 2.11 Target Cluster

I've only tested the scripts going from ES 6.8 to OS 2.11.  For my test target, I just spun up an Amazon OpenSearch Service 2.11 cluster with a master user/password combo and otherwise default settings.

### Run the scripts

I've been running them VS Code integration, but you should be able to do it using their dedicated gradle commands as well.  That would look something like:

```
SNAPSHOT_DIR=/Users/chelma/workspace/ElasticSearch/elasticsearch/distribution/build/cluster/shared/repo
LUCENE_DIR=/tmp/lucene_files
HOSTNAME=<Amazon OpenSearch Domain URL>
USERNAME=<Amazon OpenSearch Domain master user name>
PASSWORD=<Amazon OpenSearch Domain master password>

gradle build

gradle run --args='-n global_state_snapshot --snapshot-dir $SNAPSHOT_DIR -l $LUCENE_DIR -h $HOSTNAME  -u $USERNAME -p $PASSWORD -s es_6_8 -t os_2_11 --movement-type everything'
```