# reindex-from-snapshot

### Table of Contents (Generated)
- [reindex-from-snapshot](#reindex-from-snapshot)
    - [How to run](#how-to-run)
        - [Using a local snapshot directory](#using-a-local-snapshot-directory)
        - [Using an existing S3 snapshot](#using-an-existing-s3-snapshot)
        - [Using a source cluster](#using-a-source-cluster)
        - [Using Docker](#using-docker)
            - [Providing AWS permissions for S3 snapshot creation](#providing-aws-permissions-for-s3-snapshot-creation)
        - [Handling auth](#handling-auth)
    - [How to set up an ES 6.8 Source Cluster w/ an attached debugger](#how-to-set-up-an-es-68-source-cluster-w-an-attached-debugger)
    - [How to set up an ES 7.10 Source Cluster running in Docker](#how-to-set-up-an-es-710-source-cluster-running-in-docker)
        - [Setting up the Cluster w/ some sample docs](#setting-up-the-cluster-w-some-sample-docs)
    - [How to set up an OS 2.11 Target Cluster](#how-to-set-up-an-os-211-target-cluster)


## How to run

RFS provides a number of different options for running it.  We'll look at some of them below.  

### Using a local snapshot directory

In this scenario, you have a local directory on disk containing the snapshot you want to migrate.  You'll supply the `--snapshot-dir` arg, but not the ones related to a source cluster (`--source-host`, `--source-username`, `--source-password`) or S3 (`--s3-local-dir`, `--s3-repo-uri`, `--s3-region`)

```
TARGET_HOST=<source cluster URL>
TARGET_USERNAME=<master user name>
TARGET_PASSWORD=<master password>

gradle build

gradle run --args='-n global_state_snapshot --snapshot-dir ./test-resources/snapshots/ES_6_8_Single -l /tmp/lucene_files --target-host $TARGET_HOST --target-username $TARGET_USERNAME --target-password $TARGET_PASSWORD -s es_6_8 -t os_2_11 --movement-type everything'
```

### Using an existing S3 snapshot

In this scenario, you have an existing snapshot in S3 you want to migrate.  You'll supply the S3-related args (`--s3-local-dir`, `--s3-repo-uri`, `--s3-region`), but not the `--snapshot-dir` one or the ones related to a source cluster (`--source-host`, `--source-username`, `--source-password`).

```
S3_REPO_URI=<something like "s3://my-test-bucket/ES_6_8_Single/">
S3_REGION=us-east-1

TARGET_HOST=<source cluster URL>
TARGET_USERNAME=<master user name>
TARGET_PASSWORD=<master password>

gradle build

gradle run --args='-n global_state_snapshot --s3-local-dir /tmp/s3_files --s3-repo-uri $S3_REPO_URI --s3-region $S3_REGION -l /tmp/lucene_files --target-host $TARGET_HOST --target-username $TARGET_USERNAME --target-password $TARGET_PASSWORD -s es_6_8 -t os_2_11 --movement-type everything'
```

### Using a source cluster

In this scenario, you have a source cluster, and don't yet have a snapshot.  RFS will need to first make a snapshot of your source cluster, send it to S3, and then begin reindexing.  In this scenario, you'll supply the source cluster-related args (`--source-host`, `--source-username`, `--source-password`) and the S3-related args (`--s3-local-dir`, `--s3-repo-uri`, `--s3-region`), but not the `--snapshot-dir` one.

```
SOURCE_HOST=<source cluster URL>
SOURCE_USERNAME=<master user name>
SOURCE_PASSWORD=<master password>

S3_REPO_URI=<something like "s3://my-test-bucket/ES_6_8_Single/">
S3_REGION=us-east-1

TARGET_HOST=<source cluster URL>
TARGET_USERNAME=<master user name>
TARGET_PASSWORD=<master password>

gradle build

gradle run --args='-n global_state_snapshot --source-host $SOURCE_HOST --source-username $SOURCE_USERNAME --source-password $SOURCE_PASSWORD --s3-local-dir /tmp/s3_files --s3-repo-uri $S3_REPO_URI --s3-region $S3_REGION -l /tmp/lucene_files --target-host $TARGET_HOST --target-username $TARGET_USERNAME --target-password $TARGET_PASSWORD -s es_6_8 -t os_2_11 --movement-type everything'
```

### Using Docker
RFS has support for packaging its java application as a Docker image by using the Dockerfile located in the `RFS/docker` directory. This support is directly built into Gradle as well so that a user can perform the below action, and generate a fresh Docker image (`migrations/reindex_from_snapshot:latest`) with the latest local code changes available.
```shell
./gradlew buildDockerImages
```
Also built into this Docker/Gradle support is the ability to spin up a testing RFS environment using Docker compose. This compose file can be seen [here](./docker/docker-compose.yml) and includes the RFS container, a source cluster container, and a target cluster container.

This environment can be spun up with the Gradle command, and use the optional `-Pdataset` flag to preload a dataset from the `generateDatasetStage` in the multi-stage Docker [here](docker/TestSource_ES_7_10/Dockerfile). This stage will take a few minutes to run on its first attempt if it is generating data, as it will be making requests with OSB. This will be cached for future runs.
```shell
./gradlew composeUp -Pdataset=default_osb_test_workloads
```

And deleted with the Gradle command
```shell
./gradlew composeDown
```

After the Docker compose containers are created the elasticsearch/opensearch source and target clusters can be interacted with like normal. For RFS testing, a user can also load custom templates/indices/documents into the source cluster before kicking off RFS.
```shell
# To check indices on the source cluster
curl 'http://localhost:19200/_cat/indices?v'

# To check indices on the target cluster
curl 'http://localhost:29200/_cat/indices?v'
```

To kick off RFS:
```shell
docker exec -it rfs-compose-reindex-from-snapshot-1 sh -c "/rfs-app/runJavaWithClasspath.sh com.rfs.ReindexFromSnapshot --snapshot-name test-snapshot --snapshot-local-repo-dir /snapshots --min-replicas 0 --lucene-dir '/lucene' --source-host http://elasticsearchsource:9200 --target-host http://opensearchtarget:9200 --source-version es_7_10 --target-version os_2_11"
```

#### Providing AWS permissions for S3 snapshot creation

While the source cluster's container will have the `repository-s3` plugin installed out-of-the-box, to use it you'll need to provide AWS Credentials.  This plugin will either accept credential [from the Elasticsearch Keystore](https://www.elastic.co/guide/en/elasticsearch/plugins/7.10/repository-s3-client.html) or via the standard ENV variables (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, and `AWS_SESSION_TOKEN`).  The issue w/ either approach in local testing is renewal of the timeboxed creds.  One possible solution is to use an IAM User, but that is generally frowned upon.  The approach we'll take here is to accept that the test cluster is temporary, so the creds can be as well.  Therefore, we can make an AWS IAM Role in our AWS Account with the creds it needs, assume it locally to generate the credential triple, and pipe that into the container using ENV variables.

Start by making an AWS IAM Role (e.g. `arn:aws:iam::XXXXXXXXXXXX:role/testing-es-source-cluster-s3-access`) with S3 Full Access permissions in your AWS Account.  You can then get credentials with that identity good for up to one hour:

```shell
unset access_key && unset secret_key && unset session_token

output=$(aws sts assume-role --role-arn "arn:aws:iam::XXXXXXXXXXXX:role/testing-es-source-cluster-s3-access" --role-session-name "ES-Source-Cluster")

export access_key=$(echo $output | jq -r .Credentials.AccessKeyId)
export secret_key=$(echo $output | jq -r .Credentials.SecretAccessKey)
export session_token=$(echo $output | jq -r .Credentials.SessionToken)
```

The one hour limit is annoying but workable, given the only thing it's needed for is creating the snapshot at the very start of the RFS process.  This is primarily driven by the fact that IAM limits session durations to one hour when the role is assumed via another role (e.g. role chaining).  If your original creds in the AWS keyring are from an IAM User, etc, then this might not be a restriction for you and you can have up to 12 hours with the assumed creds.  Ideas on how to improve this would be greatly appreciated.

Anyways, we pipe those ENV variables into the source cluster's container via the Docker Compose file, so you can just launch the test setup as normal: 

```shell
./gradlew composeUp -Pdataset=default_osb_test_workloads
```

If you need to renew the creds, you can just kill the existing source container, renew the creds, and spin up a new container.

```
./gradlew composeDown
```

### Handling auth

RFS currently supports both basic auth (username/password) and no auth for both the source and target clusters.  To use the no-auth approach, just neglect the username/password arguments.

## How to set up an ES 6.8 Source Cluster w/ an attached debugger

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

## How to set up an ES 7.10 Source Cluster running in Docker

It is recommended to use the Docker Compose setup described above in #using-docker

### Setting up the Cluster w/ some sample docs

You can set up the cluster w/ some sample docs like so:

```
curl -X PUT "localhost:9200/_component_template/posts_template" -H "Content-Type: application/json" -d'
{
  "template": {
    "mappings": {
      "properties": {
        "contents": { "type": "text" },
        "author": { "type": "keyword" },
        "tags": { "type": "keyword" }
      }
    }
  }
}'

curl -X PUT "localhost:9200/_index_template/posts_index_template" -H "Content-Type: application/json" -d'
{
  "index_patterns": ["posts_*"],
  "template": {
    "settings": {
      "number_of_shards": 1,
      "number_of_replicas": 1
    },
    "aliases": {
      "current_posts": {}
    }
  },
  "composed_of": ["posts_template"]
}'

curl -X PUT "localhost:9200/posts_2023_02_25"

curl -X POST "localhost:9200/current_posts/_doc" -H "Content-Type: application/json" -d'
{
  "contents": "This is a sample blog post content.",
  "author": "Author Name",
  "tags": ["Elasticsearch", "Tutorial"]
}'

curl -X PUT "localhost:9200/posts_2024_01_01" -H "Content-Type: application/json" -d'
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

curl -X POST "localhost:9200/another_alias/_doc" -H "Content-Type: application/json" -d'
{
  "contents": "How Elasticsearch helped my patients",
  "author": "Tobias Funke",
  "tags": ["Elasticsearch", "Tutorial"]
}'

curl -X POST "localhost:9200/another_alias/_doc" -H "Content-Type: application/json" -d'
{
  "contents": "My Time in the Blue Man Group",
  "author": "Tobias Funke",
  "tags": ["Lifestyle"]
}'

curl -X POST "localhost:9200/another_alias/_doc" -H "Content-Type: application/json" -d'
{
  "contents": "On the Importance of Word Choice",
  "author": "Tobias Funke",
  "tags": ["Lifestyle"]
}'
```

## How to set up an OS 2.11 Target Cluster

The only target cluster version this has been tested agains is OpenSearch 2.11.  For my test target, I just spun up an Amazon OpenSearch Service 2.11 cluster with a master user/password combo and otherwise default settings.

