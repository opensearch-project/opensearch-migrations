1. Prereqs

```
mkdir -p backups/solrcloud
sudo chown -R 8983:8983 backups
export SOLR_ORBIT_DIR=/root/perf/solr-orbit
cd $SOLR_ORBIT_DIR
source .venv/bin/activate
```


1. Run Solr 6

Start the Solr 6 cluster and wait for it to come up:

```
docker compose -f docker-compose-6.yml up -d --wait
```

Create a collection called `nyc_taxis` using the `./solr_configsets/nyc_taxis_6` config.
Copy the config into node1, then create the collection across the cluster:

```
docker cp ./solr_configsets/nyc_taxis_6/conf solr-node1:/tmp/nyc_taxis_6
docker exec solr-node1 solr create_collection \
    -c nyc_taxis \
    -d /tmp/nyc_taxis_6 \
    -shards 3 \
    -replicationFactor 1 \
    -p 8983
```

Now run solr-orbit to load the data.

```
solr-orbit run \
  --pipeline=benchmark-only \
  --target-host=localhost:8983 \
  --kill-running-processes \
  --workload=nyc_taxis \
  --test-mode \
  --include-tasks="check-cluster-health,index"

```

Now trigger a backup of the three shard collection.

```
curl "http://chorus.dev.o19s.com:8985/solr/admin/collections?action=BACKUP&name=nyc_taxis_6&collection=nyc_taxis&location=/backups/solrcloud&wt=json"
```

Finally, shut down the cluster

```
docker compose -f docker-compose-6.yml down
```

1. Run Solr 7

Start the Solr 7 cluster and wait for it to come up:

```
docker compose -f docker-compose-7.yml up -d --wait
```

Create a collection called `nyc_taxis` using the `./solr_configsets/nyc_taxis_7` config.
Copy the config into node1, then create the collection across the cluster:

```
docker cp ./solr_configsets/nyc_taxis_7/conf solr-node1:/tmp/nyc_taxis_7
docker exec solr-node1 solr create_collection \
    -c nyc_taxis \
    -d /tmp/nyc_taxis_7 \
    -shards 3 \
    -replicationFactor 1 \
    -p 8983
```

Now run solr-orbit to load the data.

```
solr-orbit run \
  --pipeline=benchmark-only \
  --target-host=localhost:8983 \
  --kill-running-processes \
  --workload=nyc_taxis \
  --test-mode \
  --include-tasks="check-cluster-health,index"

```

Now trigger a backup of the three shard collection.

```
curl "http://chorus.dev.o19s.com:8985/solr/admin/collections?action=BACKUP&name=nyc_taxis_7&collection=nyc_taxis&location=/backups/solrcloud&wt=json"
```

Finally, shut down the cluster

```
docker compose -f docker-compose-7.yml down
```

1. Run Solr 8

Start the Solr 8 cluster and wait for it to come up:

```
docker compose -f docker-compose-8.yml up -d --wait
```

Create a collection called `nyc_taxis` using the `./solr_configsets/nyc_taxis_8` config.
Copy the config into node1, then create the collection across the cluster:

```
docker cp ./solr_configsets/nyc_taxis_8/conf solr-node1:/tmp/nyc_taxis_8
docker exec solr-node1 solr create_collection \
    -c nyc_taxis \
    -d /tmp/nyc_taxis_8 \
    -shards 3 \
    -replicationFactor 1 \
    -p 8983
```

Now run solr-orbit to load the data.

```
solr-orbit run \
  --pipeline=benchmark-only \
  --target-host=localhost:8983 \
  --kill-running-processes \
  --workload=nyc_taxis \
  --test-mode \
  --include-tasks="check-cluster-health,index"

```

Now trigger a backup of the three shard collection.

```
curl "http://chorus.dev.o19s.com:8985/solr/admin/collections?action=BACKUP&name=nyc_taxis_8&collection=nyc_taxis&location=/backups/solrcloud&wt=json"
curl "http://chorus.dev.o19s.com:8983/solr/nyc_taxis_shard3_replica_n4/replication?command=backup&location=/backups/solrcloud/&name=main-snapshot&wt=json"
```

Finally, shut down the cluster

```
docker compose -f docker-compose-8.yml down
```

2. Run Solr 9

```
docker compose -f docker-compose-9.yml up -d --wait
```

Create a collection called `nyc_taxis` using the `./solr_configsets/nyc_taxis_9` config.
Copy the config into node1, then create the collection across the cluster:

```
docker cp ./solr_configsets/nyc_taxis_9/conf solr-node1:/tmp/nyc_taxis_9
docker exec solr-node1 solr create_collection \
    -c nyc_taxis \
    -d /tmp/nyc_taxis_9 \
    -shards 3 \
    -replicationFactor 1 \
    -p 8983
```

Now run solr-orbit to load the data.

```
solr-orbit run \
  --pipeline=benchmark-only \
  --target-host=localhost:8983 \
  --kill-running-processes \
  --workload=nyc_taxis \
  --include-tasks="check-cluster-health,index"

```

Now trigger a backup of the three shard collection.

```
curl "http://chorus.dev.o19s.com:8985/solr/admin/collections?action=BACKUP&name=nyc_taxis_9&collection=nyc_taxis&location=/backups/solrcloud&wt=json"
```

We want a single segment version as well:

```
curl "http://chorus.dev.o19s.com:8985/solr/nyc_taxis/update?optimize=true&maxSegments=1"
```

Now trigger a backup of the three shard collection with one segment per shard.

```
curl "http://chorus.dev.o19s.com:8985/solr/admin/collections?action=BACKUP&name=nyc_taxis_9_onesegment&collection=nyc_taxis&location=/backups/solrcloud&wt=json"
```


Finally, shut down the cluster

```
docker compose -f docker-compose-9.yml down
```


3. Transfer Data up to S3

This is what MA expects as a structure on S3:
```
<s3RepoPathUri>/<snapshotName>/<collection>/
  ├── zk_backup_<N>/                         # latest N wins
  │   └── configs/<configName>/managed-schema[.xml]   # schema → OpenSearch mappings
  ├── shard_backup_metadata/
  │   ├── md_shard1_0.json, md_shard1_1.json …        # per shard; highest rev used
  │   ├── md_shard2_0.json …
  ├── backup_<N>.properties
  └── index/
      └── <uuid-named files>                  # ALL shards' segment files, dedup'd, UUID-named
```

Set the base, you need to change the `371015648411` to your specific account number.
```
S3_BASE="s3://migrations-default-371015648411-dev-us-east-1/solr-migration-test-sets"
```

Now sync each dataset up:
```
aws-cli.aws s3 sync ./backups/solrcloud/nyc_taxis_6 ${S3_BASE}/nyc_taxis_6
aws-cli.aws s3 sync ./backups/solrcloud/nyc_taxis_7 ${S3_BASE}/nyc_taxis_7
aws-cli.aws s3 sync ./backups/solrcloud/nyc_taxis_8 ${S3_BASE}/nyc_taxis_8
aws-cli.aws s3 sync ./backups/solrcloud/nyc_taxis_9 ${S3_BASE}/nyc_taxis_9
aws-cli.aws s3 sync ./backups/solrcloud/nyc_taxis_9_onesegment ${S3_BASE}/nyc_taxis_9_onesegment
```

### WE HATE YOU AND NEED A SPECIFIC SUBDIR

```
S3_BASE="s3://migrations-default-371015648411-dev-us-east-1/solr-migration-test-sets"
aws-cli.aws s3 sync ./backups/jeff ${S3_BASE}/jeff
```



4. Run Migration Assistent

Upload the config file:
```
kubectl exec -i migration-console-0 -n ma -- workflow configure edit --stdin < ./ma_configs/solrcloud/nyc_taxis_8_ma_config.json
```

Jump into the console:
```
kubectl exec -it migration-console-0 -n ma -- /bin/bash
```

Ensure no existing target cluster exists:
```
curl -X DELETE http://3.89.224.100:9200/nyc_taxis
```




kubectl exec -i migration-console-0 -n ma -- bash -c "cat > /tmp/config.json" < /Users/epugh/Documents/projects/opensearch/migrations/solr-migration-test-sets/ma_configs/config.json
