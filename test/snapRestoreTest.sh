#!/bin/bash

# Create index
echo -e "\nCreating an example index\n"
curl -X PUT "https://localhost:19200/test" -u admin:admin --insecure --header 'Content-Type: application/json' -d'
{
  "settings" : {
    "number_of_shards" : 1,
    "number_of_replicas" : 0
  }
}
'

# Index a doc
echo -e "\nIndexing an example document\n"
curl -X POST "https://localhost:19200/test/_doc/1" -u admin:admin --insecure --header 'Content-Type: application/json' -d'
{
  "field": "old_value",
  "timestamp": "2017-11-30T10:00:00"
}
'

# Create snapshot repo on source
echo -e "\nCreating snapshot repo on source cluster\n"
curl -X PUT "https://localhost:19200/_snapshot/my_backup" -u admin:admin --insecure --header 'Content-Type: application/json' -d'
{
  "type": "fs",
  "settings": {
    "location": "/usr/share/elasticsearch/snapshots",
    "compress": true
  }
}
'

# Create snapshot
echo -e "\nCreating snapshot on source cluster\n"
curl -X PUT "https://localhost:19200/_snapshot/my_backup/snapshot_1?wait_for_completion=true" -u admin:admin --insecure --header 'Content-Type: application/json'

# Copy snapshots files from ES to OS through local environment #TODO: move through cloud?
echo -e "\nCopying snapshot files from source cluster to target cluster\n"
docker cp $(docker ps --format="{{.ID}}" --filter name=elasticsearch):/usr/share/elasticsearch/snapshots .
docker cp snapshots $(docker ps --format="{{.ID}}" --filter name=opensearch):/usr/share/opensearch/snapshots


# Create snapshot repo on target
echo -e "\nCreating snapshot repo on target cluster\n"
curl -X PUT "https://localhost:29200/_snapshot/my_backup" -u admin:admin --insecure --header 'Content-Type: application/json' -d'
{
  "type": "fs",
  "settings": {
    "location": "/usr/share/opensearch/snapshots",
    "compress": true
  }
}
'

# Restore snapshot
echo -e "\nRestoring from snapshot\n"
curl -X POST "https://localhost:29200/_snapshot/my_backup/snapshot_1/_restore" -u admin:admin --insecure --header 'Content-Type: application/json'

# Wait for a moment to ensure restore completes
sleep 10

# See how document looks like now
echo -e "\nChecking how document looks like BEFORE the conflict resolution script is ran.\n"
curl -X GET "https://localhost:29200/test/_doc/1" -u admin:admin --insecure

# Run conflict resolution script with new data
echo -e "\nRunning conflict resolution script with new data\n"
curl -X POST "https://localhost:29200/test/_update/1" -u admin:admin --insecure --header 'Content-Type: application/json' -d'
{
    "script" : {
        "source": "Instant existing = Instant.parse(ctx._source.timestamp + '\''Z'\''); Instant incoming = Instant.parse(params.doc.timestamp + '\''Z'\''); if (existing.isBefore(incoming)) { ctx._source = params.doc; }",
        "lang": "painless",
        "params" : {
            "doc" : {
                "field" : "new_value",
                "timestamp" : "2017-11-30T10:07:00"
            }
        }
    }
}
'



# See how document looks like after it was updated with script.
echo -e "\nChecking how document looks like AFTER it was updated with script.\n"
curl -X GET "https://localhost:29200/test/_doc/1" -u admin:admin --insecure

echo -e "\nTesting with a document that doesn't have a newer timestamp\n"
curl -X POST "https://localhost:29200/test/_update/1" -u admin:admin --insecure --header 'Content-Type: application/json' -d'
{
    "script" : {
        "source": "Instant existing = Instant.parse(ctx._source.timestamp + '\''Z'\''); Instant incoming = Instant.parse(params.doc.timestamp + '\''Z'\''); if (existing.isBefore(incoming)) { ctx._source = params.doc; }",
        "lang": "painless",
        "params" : {
            "doc" : {
                "field" : "am_i_newer_value",
                "timestamp" : "2017-11-29T10:07:00"
            }
        }
    }
}
'

echo -e "\nChecking if document got updated after the script was ran.\n"
curl -X GET "https://localhost:29200/test/_doc/1" -u admin:admin --insecure
