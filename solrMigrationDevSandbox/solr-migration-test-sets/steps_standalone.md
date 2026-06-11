These steps run Solr in **standalone (non-cloud) mode** as a single node using plain
`docker` commands (no docker-compose, no ZooKeeper). In standalone mode you create a
**core** (not a collection) and back up via the **replication handler** (the Collections
API `action=BACKUP` only works in SolrCloud mode).

1. Prereqs

```
mkdir -p backups/standalone
sudo chown -R 8983:8983 backups
export SOLR_ORBIT_DIR=/root/perf/solr-orbit
cd $SOLR_ORBIT_DIR
source .venv/bin/activate
```


2. Run Solr 6

Start a single standalone Solr 6 node (4g heap, 8g container, shared `./backups` mount):

```
docker rm -f solr-node1 2>/dev/null
docker run -d \
    --name solr-node1 \
    -p 8983:8983 \
    --memory 16g \
    -e SOLR_HEAP=8g \
    -v "$(pwd)/backups:/backups" \
    solr:6.6.6
```

Create a core called `nyc_taxis` using the `./solr_configsets/nyc_taxis_6` config.
Copy the config into the node, then create the core:

```
docker cp ./solr_configsets/nyc_taxis_6 solr-node1:/tmp/nyc_taxis_6
docker exec solr-node1 solr create_core \
    -c nyc_taxis \
    -d /tmp/nyc_taxis_6
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

Now trigger a backup of the core (standalone replication-handler backup). This is
**asynchronous** — it returns immediately and writes to `/backups/standalone/snapshot.nyc_taxis_6`.

```
curl "http://localhost:8983/solr/nyc_taxis/replication?command=backup&location=/backups/standalone&name=nyc_taxis_6&wt=json"

# (optional) check backup status until it reports success:
curl "http://localhost:8983/solr/nyc_taxis/replication?command=details&wt=json"
```

Finally, shut down the node

```
docker rm -f solr-node1
```


3. Run Solr 7

```
docker rm -f solr-node1 2>/dev/null
docker run -d \
    --name solr-node1 \
    -p 8983:8983 \
    --memory 16g \
    -e SOLR_HEAP=8g \
    -v "$(pwd)/backups:/backups" \
    solr:7.7.3
```

Create a core called `nyc_taxis` using the `./solr_configsets/nyc_taxis_7` config:

```
docker cp ./solr_configsets/nyc_taxis_7 solr-node1:/tmp/nyc_taxis_7
docker exec solr-node1 solr create_core \
    -c nyc_taxis \
    -d /tmp/nyc_taxis_7
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

Now trigger a backup of the core.

```
curl "http://localhost:8983/solr/nyc_taxis/replication?command=backup&location=/backups/standalone&name=nyc_taxis_7&wt=json"
```

Finally, shut down the node

```
docker rm -f solr-node1
```

Reshape the snapshot into the layout MA expects: move the index files into a `nyc_taxis`
subdirectory (leaving room for `shard_backup_metadata/` alongside it).

```
DIR=./backups/standalone/snapshot.nyc_taxis_7
mkdir -p "$DIR/nyc_taxis/index"
find "$DIR" -maxdepth 1 -mindepth 1 ! -name nyc_taxis ! -name shard_backup_metadata -exec mv -t "$DIR/nyc_taxis/index" {} +
```

Generate the `shard_backup_metadata` manifest MA expects from the snapshot's files,
written into the snapshot directory:

```
DIR=./backups/standalone/snapshot.nyc_taxis_7
mkdir -p "$DIR/nyc_taxis/shard_backup_metadata"
OUT="$DIR/nyc_taxis/shard_backup_metadata/md_shard1_0.json"
echo -n '{' > "$OUT"
first=true
for f in $(ls "$DIR/nyc_taxis/index"); do
  if [ "$first" = true ]; then first=false; else echo -n ',' >> "$OUT"; fi
  echo -n "\"$f\":{\"fileName\":\"$f\"}" >> "$OUT"
done
echo -n '}' >> "$OUT"
```

Now store the schema.

```
mkdir -p ./backups/standalone/snapshot.nyc_taxis_7/nyc_taxis/zk_backup_0/configs/nyc_taxies
cp ./solr_configsets/nyc_taxis_7/conf/schema.xml ./backups/standalone/snapshot.nyc_taxis_7/nyc_taxis/zk_backup_0/configs/nyc_taxies/managed-schema
```



4. Run Solr 8

Solr 8.11+ restricts backup paths, so allow `/backups` via `SOLR_OPTS`:

```
docker rm -f solr-node1 2>/dev/null
docker run -d \
    --name solr-node1 \
    -p 8983:8983 \
    --memory 16g \
    -e SOLR_HEAP=8g \
    -e SOLR_OPTS=-Dsolr.allowPaths=/backups \
    -v "$(pwd)/backups:/backups" \
    solr:8.11.2
```

Create a core called `nyc_taxis` using the `./solr_configsets/nyc_taxis_8` config:

```
docker cp ./solr_configsets/nyc_taxis_8 solr-node1:/tmp/nyc_taxis_8
docker exec solr-node1 solr create_core \
    -c nyc_taxis \
    -d /tmp/nyc_taxis_8
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

Now trigger a backup of the core.

```
curl "http://localhost:8983/solr/nyc_taxis/replication?command=backup&location=/backups/standalone&name=nyc_taxis_8&wt=json"
```

Finally, shut down the node

```
docker rm -f solr-node1
```


Reshape the snapshot into the layout MA expects: move the index files into a `nyc_taxis`
subdirectory (leaving room for `shard_backup_metadata/` alongside it).

```
DIR=./backups/standalone/snapshot.nyc_taxis_8
mkdir -p "$DIR/nyc_taxis/index"
find "$DIR" -maxdepth 1 -mindepth 1 ! -name nyc_taxis ! -name shard_backup_metadata -exec mv -t "$DIR/nyc_taxis/index" {} +
```

Generate the `shard_backup_metadata` manifest MA expects from the snapshot's files,
written into the snapshot directory:

```
DIR=./backups/standalone/snapshot.nyc_taxis_8
mkdir -p "$DIR/nyc_taxis/shard_backup_metadata"
OUT="$DIR/nyc_taxis/shard_backup_metadata/md_shard1_0.json"
echo -n '{' > "$OUT"
first=true
for f in $(ls "$DIR/nyc_taxis/index"); do
  if [ "$first" = true ]; then first=false; else echo -n ',' >> "$OUT"; fi
  echo -n "\"$f\":{\"fileName\":\"$f\"}" >> "$OUT"
done
echo -n '}' >> "$OUT"
```

Now store the schema.

```
mkdir -p ./backups/standalone/snapshot.nyc_taxis_8/nyc_taxis/zk_backup_0/configs/nyc_taxies
cp ./solr_configsets/nyc_taxis_8/conf/schema.xml ./backups/standalone/snapshot.nyc_taxis_8/nyc_taxis/zk_backup_0/configs/nyc_taxies/managed-schema
```

5. Run Solr 9

Solr 9 binds Jetty to localhost by default and restricts backup paths, so set both
`SOLR_JETTY_HOST` and the allowed path:

```
docker rm -f solr-node1 2>/dev/null
docker run -d \
    --name solr-node1 \
    -p 8983:8983 \
    --memory 16g \
    -e SOLR_HEAP=8g \
    -e SOLR_JETTY_HOST=0.0.0.0 \
    -e SOLR_OPTS=-Dsolr.allowPaths=/backups \
    -v "$(pwd)/backups:/backups" \
    solr:9.10

```

Create a core called `nyc_taxis` using the `./solr_configsets/nyc_taxis_9` config:

```
docker cp ./solr_configsets/nyc_taxis_9 solr-node1:/tmp/nyc_taxis_9
docker exec solr-node1 solr create_core \
    -c nyc_taxis \
    -d /tmp/nyc_taxis_9
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

Now trigger a backup of the core.

```
curl "http://localhost:8983/solr/nyc_taxis/replication?command=backup&location=/backups/standalone&name=nyc_taxis_9&wt=json"
```

We want a single segment version as well:

```
curl "http://localhost:8983/solr/nyc_taxis/update?optimize=true&maxSegments=1"
```

Now trigger a backup of the core with one segment.

```
curl "http://localhost:8983/solr/nyc_taxis/replication?command=backup&location=/backups/standalone&name=nyc_taxis_9_onesegment&wt=json"
```

Finally, shut down the node

```
docker rm -f solr-node1
```


6. Transfer Data up to S3

Standalone replication-handler backups are written to `./backups/standalone/snapshot.<name>/` (the
`snapshot.` prefix is added by Solr).

Set the base, you need to change the `371015648411` to your specific account number.
```
S3_BASE="s3://migrations-default-371015648411-dev-us-east-1/solr-migration-test-sets/standalone"
```

Now sync each dataset up:
```
aws-cli.aws s3 sync ./backups/standalone/snapshot.nyc_taxis_6 ${S3_BASE}/snapshot.nyc_taxis_6
aws-cli.aws s3 sync ./backups/standalone/snapshot.nyc_taxis_7 ${S3_BASE}/snapshot.nyc_taxis_7
aws-cli.aws s3 sync ./backups/standalone/snapshot.nyc_taxis_8 ${S3_BASE}/snapshot.nyc_taxis_8
aws-cli.aws s3 sync ./backups/standalone/snapshot.nyc_taxis_9 ${S3_BASE}/snapshot.nyc_taxis_9
aws-cli.aws s3 sync ./backups/standalone/snapshot.nyc_taxis_9_onesegment ${S3_BASE}/snapshot.nyc_taxis_9_onesegment
```


7. Run Migration Assistant

Upload the config file:
```
kubectl exec -i migration-console-0 -n ma -- workflow configure edit --stdin < ./ma_configs/standalone/nyc_taxis_7_ma_config.json
```

Jump into the console:
```
kubectl exec -it migration-console-0 -n ma -- /bin/bash
```

Ensure no existing target cluster exists:
```
curl -X DELETE http://3.89.224.100:9200/nyc_taxis
```
