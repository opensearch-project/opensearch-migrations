# reindex-from-snapshot

## What is this library?

This library houses the core behavior for Reindex-From-Snapshot (RFS), a novel solution to migrating documents from one Elasticsearch/OpenSearch cluster to another.  At a high level, RFS improves the migration experience by: removing load from the source cluster during backfill migration (in comparison to normal reindexing), increasing the speed of backfill migration by parallelizing work by shard (in comparison to Data Pepper/OpenSearch Ingestion Service), enabling “hopping” across multiple major versions (in comparison to in-place upgrades), making pausing/resuming a migration trivial (in comparison to Data Pepper/OpenSearch Ingestion Service), and creating a migration path from post-fork versions of Elasticsearch to OpenSearch (not available any other way).  You can gain more context on the problem it solves by looking at [this RFC](https://github.com/opensearch-project/OpenSearch/issues/12667) and [this design doc](./docs/DESIGN.md).


The library also contains useful code for: taking snapshots of Elasticsearch clusters, parsing the contents of Elasticsearch snapshots, migrating cluster settings and configuration, and migrating index settings and configuration.

## How to use the tool

Several entrypoints are provided to handle different aspects of an overall cluster migration, housed in separate directories at the root of this repository:
* `../CreateSnapshot/`: Contains a tool that will take snapshots of a source cluster compatible with RFS
* `../MetadataMigration/`: Contains a tool that will read the contents of a snapshot and migrate the specified templates and indices to a target cluster
* `../DocumentsFromSnapshotMigration`: Contains a tool that will read the contents of a snapshot and migrates the documents in the specified indices to a target cluster

It is recommended to refer to the respective README of the tool you want to use for more details about how to use them.

## Benchmarking

This library supports benchmarks via [Java Microbenchmark Harness or JMH](https://github.com/openjdk/jmh).  These are best to be used with A/B testing that does not involve any external systems, such as string parsers.  Run the command with `./gradlew RFS:jmh` after it has completed results will be available in {project.dir}/build/reports/jmh in addition to the human readable logs.

### Adding a benchmark

It is recommended to put benchmarks into the test code, so they are validated for correctness when not run in the benchmark suite. The following shows example annotations that are used.

```java
@Test
@Benchmark
@BenchmarkMode({Mode.Throughput})
@Warmup(iterations = 0)
@Measurement(iterations = 2)
public void testJacksonParser() throws IOException {
    var successfulItems = BulkResponseParser.findSuccessDocs(bulkResponse);
    assertThat(successfulItems, hasSize(expectedSuccesses));
}
```

## How to run the full process against local test clusters

If you made some local changes to the code and want to test them beyond just running the various unit tests, try the following process to run all of the individual tools against some local Dockerized Source/Target cluster while storing the snapshot in S3.

**NOTE:** When following this process, operate out of the top level repo root directory

### Set up AWS Auth

In order to create a snapshot in S3, you'll need to create an IAM role you can assume in order to put the snapshot there.  Start by making an AWS IAM Role (e.g. `arn:aws:iam::XXXXXXXXXXXX:role/testing-es-source-cluster-s3-access`) with S3 Full Access permissions in your AWS Account.  You can then get temporary credentials with that identity good for up to one hour:

```shell
unset access_key && unset secret_key && unset session_token

output=$(aws sts assume-role --role-arn "arn:aws:iam::XXXXXXXXXXXX:role/testing-es-source-cluster-s3-access" --role-session-name "ES-Source-Cluster")

export access_key=$(echo $output | jq -r .Credentials.AccessKeyId)
export secret_key=$(echo $output | jq -r .Credentials.SecretAccessKey)
export session_token=$(echo $output | jq -r .Credentials.SessionToken)
```

These credentials will be pulled into the source cluster's Docker container in a later step in order for it to have permission to write to S3 using [the Elasticsearch S3 Repo plugin](https://www.elastic.co/guide/en/elasticsearch/plugins/7.10/repository-s3-client.html) it's configured with.

### Create an S3 Repo

You'll need to create an S3 repo via the AWS Console, the AWS CLI, or the SDK in order to store the snapshot.  There's no special requirements here as long as the IAM Role you created in the previous steps has the ability to read/write to it.  The only constraint is that the repo is empty when you begin running the RFS tools.

### Create some working directories on disk

You'll need a place on disk to download snapshot files from S3, and another **separate** directory to unpack those files into a Lucene index into.

```shell
rm -rf /tmp/lucene_files && mkdir -p /tmp/lucene_files
rm -rf /tmp/s3_files && mkdir -p /tmp/s3_files
```

### Launch the local source/target clusters

We have a gradle task to spin up some source/target clusters locally in Docker.  The source cluster starts empty.

```shell
./gradlew DocumentsFromSnapshotMigration:composeDown && ./gradlew DocumentsFromSnapshotMigration:composeUp
```

When this process completes, it will display the local network URIs for the source and target cluster:

```
+-------------------------+----------------+-----------------+
| Name                    | Container Port | Mapping         |
+-------------------------+----------------+-----------------+
| elasticsearchsource-1   | 9200           | localhost:19200 |
+-------------------------+----------------+-----------------+
| opensearchtarget-1      | 9200           | localhost:29200 |
+-------------------------+----------------+-----------------+
```

By default, this will create an Elasticsearch 7.10 source cluster, but you can get an Elasticsearch 6.8 source cluster using the commands `DocumentsFromSnapshotMigration:es68ComposeUp` and `DocumentsFromSnapshotMigration:es68ComposeDown` instead.

### Create the snapshot

You can then launch the snapshot creation tool like so:

```shell
./gradlew CreateSnapshot:run --args='--snapshot-name reindex-from-snapshot --s3-repo-uri s3://your-s3-repo-uri --s3-region us-fake-1 --source-host http://localhost:19200'
```

Be sure to point it at the source cluster rather than the target cluster.

### Migrate the metadata

You can migrate the templates/indices on the source cluster next:

```shell
./gradlew MetadataMigration:run --args='--snapshot-name reindex-from-snapshot --s3-local-dir /tmp/s3_files --s3-repo-uri s3://your-s3-repo-uri --s3-region us-fake-1 --target-host http://localhost:29200'
```

Be sure to point it at the target cluster rather than the source cluster.

### Migrate the documents

Finally, you can migrate the documents:

```shell
./gradlew DocumentsFromSnapshotMigration:run --args='--snapshot-name reindex-from-snapshot --s3-local-dir /tmp/s3_files --s3-repo-uri s3://your-s3-repo-uri --s3-region us-fake-1 --lucene-dir /tmp/lucene_files --target-host http://localhost:29200'
```

Each execution of this last tool moves the documents from a single shard of the source snapshot before exiting.  To migrate all the shards of the source snapshot, keep running the tool until it throws a `NoWorkLeftException`.  You can also look at the work coordination metadata stored in a special index created on the target cluster, but that requires specialized knowledge.

## How to run an ES 6.8 Source Cluster w/ an attached debugger

```shell
git clone git@github.com:elastic/elasticsearch.git
git checkout 6.8
```

Open up IntelliJ and load the root directory.  Then follow [these instructions](https://www.elastic.co/blog/how-to-debug-elasticsearch-source-code-in-intellij-idea) to run Elasticsearch from source w/ the debugger attached.

Then, you can run some commands to set up some test indices/snapshot:

```shell
curl -u "elastic-admin:elastic-password" -X GET "http://localhost:9200/"
```

## How to run an ES 7.10 Source Cluster w/ an attached debugger

The process is the same as for 6.8; see [that guide](#how-to-run-an-es-68-source-cluster-w-an-attached-debugger).

