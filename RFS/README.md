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

## Native bulk transformations

Java document providers can implement the functional interface
[`BulkOperationTransformer`](src/main/java/org/opensearch/migrations/bulkload/transformers/BulkOperationTransformer.java)
and return it through the existing `IJsonTransformerProvider` service interface.
Its `transformOperations` method receives typed bulk metadata, source context, and
unparsed document bodies. Versioning, metadata edits, filtering, and body changes
use the same contract. `getSourceHints()` exposes every original `Document.hints()`
entry, including keys unknown to the sink; `getSourceMetadata()` exposes arbitrary
source metadata such as the snapshot's `_version`. Source adapters supply these
values, and new keys require no changes to the transformation interface.

For example, a source adapter can supply a `tenantRouting` hint and a Java
transformation can use it to set routing for index writes:

```java
BulkOperationTransformer routeByTenant = operations -> {
    for (var operation : operations) {
        var hints = operation.getSourceHints();
        if (operation instanceof IndexOp index && hints != null && hints.containsKey("tenantRouting")) {
            index.getOperation().setRouting(hints.get("tenantRouting"));
        }
    }
    return operations;
};
```

Hints and source metadata are input context and are never emitted as bulk action
fields. Use the typed operation setters to change outgoing metadata. Native
conversion retains the existing hint and metadata maps by reference without
parsing the body. JSON transformations receive the same context as `source_hints`
and `source_metadata`.

Register the provider with the existing Java service loader and select it with
`--doc-transformer-config`. The loader returns a single configured transformer
directly. RFS requests `getNativeStages(BulkOperationTransformer.class)` once when
constructing the sink; the shared transformer interfaces handle composition.
This capability query also supports other typed transformation contracts without
depending on RFS or versioning.

Metadata changes can edit `IndexOp` or `DeleteOp` directly without creating body
Maps. Calling `operation.getDocument()` materializes a mutable Map and switches
that operation to normal JSON serialization. To replace a body with already
encoded JSON, use `setRawDocument(byte[])`; treat the original byte arrays as
immutable. The original source is retained separately for failed-document records.
To filter, reorder, or add operations, return a new list; operations themselves
can be edited in place.

All stages must be native to retain the raw path. Mixed Java/JavaScript chains
use the existing JSON representation and preserve configuration order.
`BulkOperationJsonAdapter` contains the JSON conversion; native execution bypasses
it. Custom wrappers can expose a native contract through `getNativeStages` only
when that contract preserves their full behavior.

The built-in `BulkVersioningTransformerProvider` supports `internal`, `external`,
and `external_gte`; see the
[startup examples](../DocumentsFromSnapshotMigration/README.md#native-java-versioning).

## Large documents

A document larger than `--documents-size-per-bulk-request` is normally sent alone, in one request of
whatever size the document needs. A target that caps request size (a proxy, `http.max_content_length`,
a managed-service limit) rejects that request and the document fails.

`--split-large-documents` (off by default, OpenSearch targets only) sends such a document as several
bulk requests that each fit the limit. Documents that fit are sent exactly as before; the option adds
one size comparison per bulk request to that path.

1. The document's JSON is cut between whole top-level fields. A field holding an object too large for
   one request is cut between that object's own fields. Field bytes are copied, never re-encoded.
2. The first fragment is written with a bulk `create`. If the ID already exists, its position is read
   and the fragment is written with an `index` conditional on that `if_seq_no` / `if_primary_term`,
   so the existing document is replaced exactly once.
3. Each remaining fragment is a bulk `update` with a partial `doc`, which the target merges into the
   stored document. These merges are idempotent, so a retried request changes nothing.

Every request uses the ordinary bulk path: the same retries, compression, metrics and failed document
stream. One extra `GET <index>` is made per split document.

A document is sent whole, as it is without the option, and a warning is logged when:

- the operation is not a plain index with an explicit ID (server-generated IDs, external versioning or
  any other bulk option, a `_type`);
- a single string, number or array is larger than one request, a field name repeats within an object,
  or a fractional number is not exactly representable as a 64-bit float (the target rewrites the
  stored `_source` on every merge and would round it);
- the target index disables, filters or derives `_source`, or sets a default or final ingest pipeline.

Limitations to weigh before enabling it:

- The document is searchable while incomplete, between the first and last request.
- If the target rejects a later fragment, for example a mapping error, the document is reported once to the
  failed document stream with its whole source, and the fields already merged stay on the target
  until that document is migrated again.
- The stored `_source` is the target's re-serialization of the merged document: the same fields and
  values, but not byte-for-byte the source text (`1.50` is stored as `1.5`).
- An object the target must receive in one piece, such as one very large `geo_shape`, is still cut
  between its fields, and the target is expected to reject the incomplete piece (see the previous point).
- Amazon OpenSearch Serverless has not been verified. It does not support `if_seq_no`, so an existing
  document there is replaced unconditionally.

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
./gradlew DocumentsFromSnapshotMigration:run --args="\
  --snapshot-name reindex-from-snapshot \
  --s3-local-dir /tmp/s3_files \
  --s3-repo-uri s3://your-s3-uri \
  --s3-region us-fake-1 \
  --lucene-dir /tmp/lucene_files \
  --target-host http://hostname:9200" \
  || { exit_code=$?; [[ $exit_code -ne 3 ]] && echo "Command failed with exit code $exit_code. Consider rerunning the command."; }
```

Each execution of this last tool moves the documents from a single shard of the source snapshot before exiting.  To migrate all the shards of the source snapshot, keep running the tool until it throws a `NoWorkLeftException`.  You can also look at the work coordination metadata stored in a special index created on the coordinator cluster (a dedicated single-node OpenSearch cluster by default), but that requires specialized knowledge.

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
