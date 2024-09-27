## Test Resources

This directory tree contains a number of different resources for use in testing of RFS.  A breakdown is as follows.

### Snapshots

#### ES_5_6_Updates_Deletes
An Elastic 5.6 snapshot repo containing a single index with a collection of documents that variously been updated, deleted, or both.  It contains multiple type mappings.  The commands used to generate the snapshot are as follows:

```
curl -X PUT "localhost:19200/test_updates_deletes" -H "Content-Type: application/json" -d '
{
  "settings": {
    "index": {
      "number_of_shards": 1,
      "number_of_replicas": 0
    }
  },
  "mappings": {
    "type1": {
      "properties": {
        "title": { "type": "text" }
      }
    },
    "type2": {
      "properties": {
        "contents": { "type": "text" }
      }
    }
  }
}'

curl -X PUT "localhost:19200/test_updates_deletes/type1/complexdoc" -H "Content-Type: application/json" -d '
{
  "title": "This is a doc with complex history"
}
'

curl -X POST "localhost:19200/test_updates_deletes/_flush"

curl -X DELETE "localhost:19200/test_updates_deletes/type1/complexdoc"

curl -X POST "localhost:19200/test_updates_deletes/_flush"

curl -X PUT "localhost:19200/test_updates_deletes/type1/complexdoc" -H "Content-Type: application/json" -d '
{
  "title": "This is a doc with complex history"
}
'

curl -X POST "localhost:19200/test_updates_deletes/_flush"

curl -X POST "localhost:19200/test_updates_deletes/type1/complexdoc/_update" -H "Content-Type: application/json" -d '
{
  "doc": {
    "title": "This is a doc with complex history. Updated!"
  }
}
'

curl -X POST "localhost:19200/test_updates_deletes/_flush"

curl -X PUT "localhost:19200/test_updates_deletes/type1/deleteddoc" -H "Content-Type: application/json" -d '
{
  "title": "This doc that will be deleted"
}
'

curl -X POST "localhost:19200/test_updates_deletes/_flush"

curl -X DELETE "localhost:19200/test_updates_deletes/type1/deleteddoc"

curl -X POST "localhost:19200/test_updates_deletes/_flush"

curl -X PUT "localhost:19200/test_updates_deletes/type2/updateddoc" -H "Content-Type: application/json" -d '
{
  "content": "blah blah"
}
'

curl -X POST "localhost:19200/test_updates_deletes/_flush"

curl -X POST "localhost:19200/test_updates_deletes/type2/updateddoc/_update" -H "Content-Type: application/json" -d '
{
  "doc": {
    "content": "Updated!"
  }
}
'

curl -X POST "localhost:19200/test_updates_deletes/_flush"

curl -X PUT "localhost:19200/test_updates_deletes/type2/unchangeddoc" -H "Content-Type: application/json" -d '


{
  "content": "This doc will not be changed\nIt has multiple lines of text\nIts source doc has extra newlines."
}

'

curl -X POST "localhost:19200/test_updates_deletes/_flush"

curl -X PUT "localhost:19200/_snapshot/test_repository" -H "Content-Type: application/json" -d '{
  "type": "fs",
  "settings": {
    "location": "/snapshots",
    "compress": false
  }
}'

curl -X PUT "localhost:19200/_snapshot/test_repository/rfs_snapshot" -H "Content-Type: application/json" -d '{
  "indices": "test_updates_deletes",
  "ignore_unavailable": true,
  "include_global_state": true
}'
```

#### ES_6_8_Single
An Elasticsearch 6.8 snapshot repo containing a single snapshot, `global_state_snapshot`.  Contains two indices (`posts_2023_02_25`, `posts_2024_01_01`), each with few documents in them.  Contains a template, `posts_index_template`.

#### ES_6_8_Updates_Deletes_Merged
This snapshot is the result of taking the [ES_5_6_Updates_Deletes](#es_5_6_updates_deletes) snapshot, restoring in an Elasticsearch 6.8 cluster, and performing a force-merge on its single index, and taking a new snapshot:

```
curl -X POST "localhost:19200/test_updates_deletes/_forcemerge?max_num_segments=1"
```

This process forces the original Lucene 6 segment files from the ES 5.6 snapshot to be re-written as a single Lucene 7 segment, but leaves the index settings untouched.  As a result, it will be readable by a Lucene 8 reader also compatible with snapshots created by ES 7 clusters.  However - it also contains a multi-type mapping in its single index, which could not be created in ES 6.8 alone.

#### ES_6_8_Updates_Deletes_Native
An Elastic 6.8 snapshot repo containing a single index that was created natively on a ES 6.8 cluster, with a collection of documents that variously been updated, deleted, or both.  `_flush`'s were used after each operation to try to ensure the maximum number of segments possible.  The commands used to generate the snapshot are as follows:

```
curl -X PUT "localhost:19200/test_updates_deletes" -H "Content-Type: application/json" -d '
{
  "settings": {
    "index": {
      "number_of_shards": 1,
      "number_of_replicas": 0
    }
  }
}'

curl -X PUT "localhost:19200/test_updates_deletes/_doc/complexdoc" -H "Content-Type: application/json" -d '
{
  "title": "This is a doc with complex history",
  "content": "blah blah"
}
'

curl -X POST "localhost:19200/test_updates_deletes/_flush"

curl -X DELETE "localhost:19200/test_updates_deletes/_doc/complexdoc"

curl -X POST "localhost:19200/test_updates_deletes/_flush"

curl -X PUT "localhost:19200/test_updates_deletes/_doc/complexdoc" -H "Content-Type: application/json" -d '
{
  "title": "This is a doc with complex history",
  "content": "blah blah"
}
'

curl -X POST "localhost:19200/test_updates_deletes/_flush"

curl -X POST "localhost:19200/test_updates_deletes/_doc/complexdoc/_update" -H "Content-Type: application/json" -d '
{
  "doc": {
    "content": "Updated!"
  }
}
'

curl -X POST "localhost:19200/test_updates_deletes/_flush"

curl -X PUT "localhost:19200/test_updates_deletes/_doc/deleteddoc" -H "Content-Type: application/json" -d '
{
  "title": "This doc that will be deleted",
  "content": "bleh bleh"
}
'

curl -X POST "localhost:19200/test_updates_deletes/_flush"

curl -X DELETE "localhost:19200/test_updates_deletes/_doc/deleteddoc"

curl -X POST "localhost:19200/test_updates_deletes/_flush"

curl -X PUT "localhost:19200/test_updates_deletes/_doc/updateddoc" -H "Content-Type: application/json" -d '
{
  "title": "This is doc that will be updated",
  "content": "blih blih"
}
'

curl -X POST "localhost:19200/test_updates_deletes/_flush"

curl -X POST "localhost:19200/test_updates_deletes/_doc/updateddoc/_update" -H "Content-Type: application/json" -d '
{
  "doc": {
    "content": "Updated!"
  }
}
'

curl -X POST "localhost:19200/test_updates_deletes/_flush"

curl -X PUT "localhost:19200/test_updates_deletes/_doc/unchangeddoc" -H "Content-Type: application/json" -d '


{
  "title": "This doc will not be changed\nIt has multiple lines of text\nIts source doc has extra newlines.",
  "content": "bluh bluh"
}

'

curl -X POST "localhost:19200/test_updates_deletes/_flush"

curl -X PUT "localhost:19200/_snapshot/test_repository" -H "Content-Type: application/json" -d '{
  "type": "fs",
  "settings": {
    "location": "/snapshots",
    "compress": false
  }
}'

curl -X PUT "localhost:19200/_snapshot/test_repository/rfs_snapshot" -H "Content-Type: application/json" -d '{
  "indices": "test_updates_deletes",
  "ignore_unavailable": true,
  "include_global_state": true
}'
```

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

curl -X PUT "localhost:19200/_snapshot/test_repository" -H "Content-Type: application/json" -d '{
  "type": "fs",
  "settings": {
    "location": "/snapshots",
    "compress": false
  }
}'

curl -X PUT "localhost:19200/_snapshot/test_repository/rfs_snapshot" -H "Content-Type: application/json" -d '{
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

#### ES_7_10_BWC_Check
An Elastic 7.10 snapshot repo designed to exercise backwards compatibility across a couple different features.  It contains two indices; one is created using pre-ES 7.8 templates and pre-ES 7.0 type declarations, and the other is created using forward-compatible index/component templates and no type declarations. Both indices contain a single document.  It also contains two indices with either no mappings or an empty mapping entry and no documents; this exercises an edge case where there will be a mappings entry in the snapshot with no contents.

```
curl -X PUT "localhost:19200/_template/bwc_template?include_type_name=true" -H "Content-Type: application/json" -d '
{
  "index_patterns": ["bwc_index*"],
  "settings": {
    "index": {
      "number_of_shards": 1,
      "number_of_replicas": 0
    }
  },
  "mappings": {
    "arbitrary_type": {
      "properties": {
        "title": {
          "type": "text"
        },
        "content": {
          "type": "text"
        }
      }
    }
  },
  "aliases": {
    "bwc_alias": {}
  }
}'

curl -X PUT "localhost:19200/bwc_index_1" -H "Content-Type: application/json"

curl -X PUT "localhost:19200/bwc_alias/_doc/bwc_doc" -H "Content-Type: application/json" -d '
{
  "title": "This is a doc in a backwards compatible index",
  "content": "Four score and seven years ago"
}'

curl -X PUT "localhost:19200/_component_template/fwc_mappings" -H "Content-Type: application/json" -d '
{
  "template": {
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
  }
}'

curl -X PUT "localhost:19200/_component_template/fwc_settings" -H "Content-Type: application/json" -d '
{
  "template": {
    "settings": {
      "index": {
        "number_of_shards": 1,
        "number_of_replicas": 0
      }
    }
  }
}'

curl -X PUT "localhost:19200/_index_template/fwc_template" -H "Content-Type: application/json" -d '
{
  "index_patterns": ["fwc_index*"],
  "composed_of": [
    "fwc_mappings",
    "fwc_settings"
  ],
  "template": {
    "aliases": {
      "fwc_alias": {}
    }
  }
}'

curl -X PUT "localhost:19200/fwc_index_1" -H "Content-Type: application/json"

curl -X PUT "localhost:19200/fwc_alias/_doc/fwc_doc" -H "Content-Type: application/json" -d '
{
  "title": "This is a doc in a forward compatible index",
  "content": "Life, the Universe, and Everything"
}'

curl -X PUT "localhost:19200/no_mappings_no_docs" -H "Content-Type: application/json" -d '
{
  "settings": {
    "index": {
      "number_of_shards": 1,
      "number_of_replicas": 0
    }
  }
}'

curl -X PUT "localhost:19200/empty_mappings_no_docs" -H "Content-Type: application/json" -d '
{
  "settings": {
    "index": {
      "number_of_shards": 1,
      "number_of_replicas": 0
    }
  },
  "mappings": {
    "properties":{
    }
  }
}'

curl -X PUT "localhost:19200/_snapshot/test_repository" -H "Content-Type: application/json" -d '{
  "type": "fs",
  "settings": {
    "location": "/snapshots",
    "compress": false
  }
}'

curl -X PUT "localhost:19200/_snapshot/test_repository/rfs-snapshot" -H "Content-Type: application/json" -d '{
  "indices": "bwc_index_1,fwc_index_1,no_mappings_no_docs,empty_mappings_no_docs",
  "ignore_unavailable": true,
  "include_global_state": true
}'
```