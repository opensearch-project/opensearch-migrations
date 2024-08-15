## Test Resources

This directory tree contains a number of different resources for use in testing of RFS.  A breakdown is as follows.

### Snapshots

#### ES_6_8_Single
An Elasticsearch 6.8 snapshot repo containing a single snapshot, `global_state_snapshot`.  Contains two indices (`posts_2023_02_25`, `posts_2024_01_01`), each with few documents in them.  Contains a template, `posts_index_template`.

#### ES_7_10_Single
An Elasticsearch 7.10 snapshot repo containing a single snapshot (`global_state_snapshot`).  Contains two indices (`posts_2023_02_25`, `posts_2024_01_01`), each with few documents in them.  Contains an index template, `posts_index_template`, and a composite template, `posts_template`.

#### ES_7_10_Double
An Elasticsearch 7.10 snapshot repo containing a two snapshots (`global_state_snapshot`, `global_state_snapshot_2`).  `global_state_snapshot` contains two indices (`posts_2023_02_25`, `posts_2024_01_01`), each with few documents in them, an index template, `posts_index_template`, and a composite template, `posts_template`.  `global_state_snapshot_2` is the same, except it doesn't have the index `posts_2023_02_25` in it.

#### ES_7_10_Updates_Deletes_w_Soft
An Elastic 7.10 snapshot repo containing a single index with a collection of documents that variously been updated, deleted, or both.  Soft deletes are enabled on the index by default.  The commands used to generate the snapshot are as follows:

```
curl -X PUT "localhost:19200/test_updates_deletes/_doc/complexdoc" -H "Content-Type: application/json" -d '
{
  "title": "This is a doc with complex history",
  "content": "blah blah"
}
'

curl -X DELETE "localhost:19200/test_updates_deletes/_doc/complexdoc"

curl -X PUT "localhost:19200/test_updates_deletes/_doc/complexdoc" -H "Content-Type: application/json" -d '
{
  "title": "This is a doc with complex history",
  "content": "blah blah"
}
'

curl -X POST "localhost:19200/test_updates_deletes/_update/complexdoc" -H "Content-Type: application/json" -d '
{
  "doc": {
    "content": "Updated!"
  }
}
'

curl -X PUT "localhost:19200/test_updates_deletes/_doc/deleteddoc" -H "Content-Type: application/json" -d '
{
  "title": "This doc that will be deleted",
  "content": "bleh bleh"
}
'

curl -X DELETE "localhost:19200/test_updates_deletes/_doc/deleteddoc"

curl -X PUT "localhost:19200/test_updates_deletes/_doc/updateddoc" -H "Content-Type: application/json" -d '
{
  "title": "This is doc that will be updated",
  "content": "blih blih"
}
'

curl -X POST "localhost:19200/test_updates_deletes/_update/updateddoc" -H "Content-Type: application/json" -d '
{
  "doc": {
    "content": "Updated!"
  }
}
'

curl -X PUT "localhost:19200/test_updates_deletes/_doc/unchangeddoc" -H "Content-Type: application/json" -d '


{
  "title": "This doc will not be changed\nIt has multiple lines of text\nIts source doc has extra newlines.",
  "content": "bluh bluh"
}

'

curl -X PUT "localhost:19200/_snapshot/test_s3_repository" -H "Content-Type: application/json" -d '{
  "type": "s3",
  "settings": {
    "bucket": "chelma-iad-rfs-local-testing",
    "region": "us-east-1"
  }
}'

curl -X PUT "localhost:19200/_snapshot/test_s3_repository/rfs_snapshot" -H "Content-Type: application/json" -d '{
  "indices": "test_updates_deletes",
  "ignore_unavailable": true,
  "include_global_state": true
}'
```

#### ES_7_10_Updates_Deletes_wo_Soft
An Elastic 7.10 snapshot repo containing a single index with a collection of documents that variously been updated, deleted, or both.  The creation process was identical to [ES_7_10_Updates_Deletes_w_Soft](#es_7_10_updates_deletes_w_soft), except that before adding the test documents the Index was configured to disable soft-deletes like so:

```
curl -X PUT "localhost:19200/test_updates_deletes" -H "Content-Type: application/json" -d '
{
  "settings": {
    "index": {
      "soft_deletes": {
        "enabled": false
      },
      "number_of_shards": 1,
      "number_of_replicas": 1
    }
  },
  "mappings": {
    "properties": {
      "title": {
        "type": "text"
      },
      "content": {
        "type": "text"
      }
    }
  }
}'
```