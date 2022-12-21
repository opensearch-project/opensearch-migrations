# Snapshot & Restore in Docker

## Setup

- Docker running
- Two docker-compose files--one with your source image, second with your destination image.
	- Both should be using a volume called--in this case `elasticsearch-snapshot` (because the source cluster in this example is elasticsearch)
	- Specify the ports for at least one of the clusters (e.g. `9202:9200`) so that they can be addressed independently. In this particular case, the default setting for ES is to have security off and for OS is to have security on, so the ES cluster can be reached at `http://localhost:9200` and the OS cluster at `https://localhost:9202`. Because of the default security settings, a username and password are required to talk to the OS cluster, but not the ES cluster.
- Sample data set, etc. etc.

## Steps

For this example, I'm using the files:
`docker-compose.example.es_7_10_2.yaml` as the source and `docker-compose.example.os_1_0.yaml` as the destination


### Step 0
Start with a clean slate.
```
docker container stop $(docker container ls -aq) ; docker container rm $(docker container ls -aq) ; docker volume rm -f $(docker volume ls -q) ; docker network rm $(docker network ls -q)
```

### Step 1
Stand up the source cluster and verify that it's running. The `docker-compose` command sets the project name with `-p` and `--detach` runs the containers in the background.
```
--> docker-compose -f docker-compose.example.es_7_10_2.yaml -p snapshot-test up --detach

--> curl 'localhost:9200/_cat/nodes?pretty' -ku 'admin:admin'
172.19.0.2 10 82 4 0.23 0.18 0.07 dimr - elasticsearch-node1
172.19.0.3 13 82 2 0.23 0.18 0.07 dimr * elasticsearch-node2

--> curl 'localhost:9200?pretty'
{
  "name" : "elasticsearch-node1",
  "cluster_name" : "es-cluster",
  "cluster_uuid" : "zmv4zOp2SY21fo8v2dybOA",
  "version" : {
    "number" : "7.10.2",
    "build_flavor" : "oss",
    "build_type" : "docker",
    "build_hash" : "747e1cc71def077253878a59143c1f785afa92b9",
    "build_date" : "2021-01-13T00:42:12.435326Z",
    "build_snapshot" : false,
    "lucene_version" : "8.7.0",
    "minimum_wire_compatibility_version" : "6.8.0",
    "minimum_index_compatibility_version" : "6.0.0-beta1"
  },
  "tagline" : "You Know, for Search"
}
```

### Step 2
Add permssions for the container user to access the snapshot volume.
```
──> docker exec -u 0:0 elasticsearch-node1 chown -R elasticsearch /opt/elasticsearch/snapshots
```

### Step 3
Add data to the source cluster and verify that it's present.

```
──> ./csv_to_os.py mini-nyc-taxi-data.csv --index taxi --host "http://localhost" --port 9200 --index-settings example/nyc-taxi-settings.json
Args:
input_file: mini-nyc-taxi-data.csv
index_settings: example/nyc-taxi-settings.json
index: taxi
host: http://localhost
port: 9200
user: admin:admin

Creating the OpenSearch client.
Creating index.
{'acknowledged': True, 'shards_acknowledged': True, 'index': 'taxi'}
Loading csv.
<class 'pandas.core.frame.DataFrame'>
RangeIndex: 49999 entries, 0 to 49998
Columns: 8 entries, id to pickup_location
dtypes: int64(3), object(5)
memory usage: 3.1+ MB
None
Preparing paginated bulk inserts.
The records will be split into 5 pages of up to 10000 records.
100%|██████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████████| 5/5 [00:07<00:00,  1.43s/it]

──> curl 'localhost:9200/taxi/_count?pretty' -ku 'admin:admin'
{
  "count" : 49999,
  "_shards" : {
    "total" : 1,
    "successful" : 1,
    "skipped" : 0,
    "failed" : 0
  }
}
```

### Step 4
Register the snapshot location
```
──> curl -XPUT 'localhost:9200/_snapshot/es_backup' -H 'Content-Type: application/json' -d '{
	"type": "fs",
		"settings": {
			"location": "/opt/elasticsearch/snapshots"
	}
}'
{"acknowledged":true}%
```

### Step 5
Create a snapshot. (In the example here, I actually have 2 snapshots--one with two indices, and a second where I deleted one of the indices)
```
--> curl -XPUT 'localhost:9200/_snapshot/es_backup/1'
{"accepted":true}%

──> curl 'localhost:9200/_snapshot/es_backup/1?pretty' -ku 'admin:admin'
{
  "snapshots" : [
    {
      "snapshot" : "1",
      "uuid" : "Qynez6hhQLmC7UIlNHMVzQ",
      "version_id" : 7100299,
      "version" : "7.10.2",
      "indices" : [
        "taxi",
        "nyc-taxi-data"
      ],
      "data_streams" : [ ],
      "include_global_state" : true,
      "state" : "SUCCESS",
      "start_time" : "2022-10-28T17:24:27.653Z",
      "start_time_in_millis" : 1666977867653,
      "end_time" : "2022-10-28T17:24:28.255Z",
      "end_time_in_millis" : 1666977868255,
      "duration_in_millis" : 602,
      "failures" : [ ],
      "shards" : {
        "total" : 2,
        "failed" : 0,
        "successful" : 2
      }
    }
  ]
}

──> curl 'localhost:9200/_snapshot/es_backup/_all?pretty' -ku 'admin:admin'
{
  "snapshots" : [
    {
      "snapshot" : "1",
      "uuid" : "Qynez6hhQLmC7UIlNHMVzQ",
      "version_id" : 7100299,
      "version" : "7.10.2",
      "indices" : [
        "taxi",
        "nyc-taxi-data"
      ],
      "data_streams" : [ ],
      "include_global_state" : true,
      "state" : "SUCCESS",
      "start_time" : "2022-10-28T17:24:27.653Z",
      "start_time_in_millis" : 1666977867653,
      "end_time" : "2022-10-28T17:24:28.255Z",
      "end_time_in_millis" : 1666977868255,
      "duration_in_millis" : 602,
      "failures" : [ ],
      "shards" : {
        "total" : 2,
        "failed" : 0,
        "successful" : 2
      }
    },
    {
      "snapshot" : "2",
      "uuid" : "7h1Qw3OBRwe2ZV4y7QJO_A",
      "version_id" : 7100299,
      "version" : "7.10.2",
      "indices" : [
        "taxi"
      ],
      "data_streams" : [ ],
      "include_global_state" : true,
      "state" : "SUCCESS",
      "start_time" : "2022-10-28T17:26:10.783Z",
      "start_time_in_millis" : 1666977970783,
      "end_time" : "2022-10-28T17:26:10.783Z",
      "end_time_in_millis" : 1666977970783,
      "duration_in_millis" : 0,
      "failures" : [ ],
      "shards" : {
        "total" : 1,
        "failed" : 0,
        "successful" : 1
      }
    }
  ]
}
```

### Step 6
Stand up the destination cluster and verify that it's running (note that I'm addressing port 9202 (and that SSL is enabled in this case)).
```
--> docker-compose -f docker-compose.example.os_1_0.yaml -p snapshot-test up --detach

--> curl 'https://localhost:9202/?pretty' -ku 'admin:admin'                                                                                         7 ↵ ──(Fri,Oct28)─┘
{
  "name" : "opensearch-node1",
  "cluster_name" : "opensearch-cluster",
  "cluster_uuid" : "Kqrq24KPTKmqx7XoqvluAg",
  "version" : {
    "distribution" : "opensearch",
    "number" : "1.0.0",
    "build_type" : "tar",
    "build_hash" : "34550c5b17124ddc59458ef774f6b43a086522e3",
    "build_date" : "2021-07-02T23:22:21.383695Z",
    "build_snapshot" : false,
    "lucene_version" : "8.8.2",
    "minimum_wire_compatibility_version" : "6.8.0",
    "minimum_index_compatibility_version" : "6.0.0-beta1"
  },
  "tagline" : "The OpenSearch Project: https://opensearch.org/"
}
```

### Step 7
Register the snapshot repo for the destination cluster, and verify that it can see the previously created snapshots.
```
──> curl -XPUT 'https://localhost:9202/_snapshot/es_backup' -ku 'admin:admin' -H 'Content-Type: application/json' -d '{
"type": "fs",
	"settings": {
	 	"location": "/opt/elasticsearch/snapshots"
	}
}'

──> curl 'https://localhost:9202/_snapshot/es_backup/_all?pretty' -ku 'admin:admin'
{
  "snapshots" : [
    {
      "snapshot" : "1",
      "uuid" : "Qynez6hhQLmC7UIlNHMVzQ",
      "version_id" : 7100299,
      "version" : "7.10.2",
      "indices" : [
        "taxi",
        "nyc-taxi-data"
      ],
      "data_streams" : [ ],
      "include_global_state" : true,
      "state" : "SUCCESS",
      "start_time" : "2022-10-28T17:24:27.653Z",
      "start_time_in_millis" : 1666977867653,
      "end_time" : "2022-10-28T17:24:28.255Z",
      "end_time_in_millis" : 1666977868255,
      "duration_in_millis" : 602,
      "failures" : [ ],
      "shards" : {
        "total" : 2,
        "failed" : 0,
        "successful" : 2
      }
    },
    {
      "snapshot" : "2",
      "uuid" : "7h1Qw3OBRwe2ZV4y7QJO_A",
      "version_id" : 7100299,
      "version" : "7.10.2",
      "indices" : [
        "taxi"
      ],
      "data_streams" : [ ],
      "include_global_state" : true,
      "state" : "SUCCESS",
      "start_time" : "2022-10-28T17:26:10.783Z",
      "start_time_in_millis" : 1666977970783,
      "end_time" : "2022-10-28T17:26:10.783Z",
      "end_time_in_millis" : 1666977970783,
      "duration_in_millis" : 0,
      "failures" : [ ],
      "shards" : {
        "total" : 1,
        "failed" : 0,
        "successful" : 1
      }
    }
  ]
}
```

### Step 8
Restore the snapshot to the destination cluster and verify that the data is present.
```
──> curl -XPOST 'https://localhost:9202/_snapshot/es_backup/2/_restore?pretty' -ku 'admin:admin'
{
  "accepted" : true
}

──> curl 'https://localhost:9202/taxi/_count?pretty' -ku 'admin:admin'
{
  "count" : 49999,
  "_shards" : {
    "total" : 1,
    "successful" : 1,
    "skipped" : 0,
    "failed" : 0
  }
}
```
