# Documents from Snapshot Migration (RFS)

## What is this tool?

This tool exposes the underlying Reindex-From-Snapshot (RFS) core library in a executable that will migrate the documents from a specified snapshot to a target cluster.  Very briefly, the application will parse the contents of the specified snapshot, pick a shard from the snapshot, then extract the documents from the shard and move them to the target cluster.  After moving the contents of the shard, the application exits.  It is intended to be run iteratively until there are no more shards left to migrate.  The application keeps track of which shards have been moved by storing metadata in a special index on the target cluster.  The metadata serves as a point of coordination when many instances of this application are running simultaneously to do things like determining which instance migrates which shard, reducing the amount of duplicated work, retrying failures, etc.  You can read a lot more about this in [the RFS Design Doc](../RFS/docs/DESIGN.md).

The snapshot the application extracts the documents from can be local or in S3.  You'll need network access to the target cluster because the application uses the standard REST API on the cluster to ingest the extracted documents.

## How to use the tool

You can kick off locally using Gradle. These worker are designed to be run multiple times to fully migrate a cluster.

### S3 Snapshot

From the root directory of the repo, run a CLI command like so:

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

In order for this succeed, you'll need to make sure you have valid AWS Credentials in your key ring (~/.aws/credentials) with permission to operate on the S3 URI specified

### On-Disk Snapshot

From the root directory of the repo, run a CLI command like so:

```shell
./gradlew DocumentsFromSnapshotMigration:run --args="\
  --snapshot-name reindex-from-snapshot \
  --snapshot-local-dir /snapshot \
  --lucene-dir /tmp/lucene_files \
  --target-host http://hostname:9200" \
  || { exit_code=$?; [[ $exit_code -ne 3 ]] && echo "Command failed with exit code $exit_code. Consider rerunning the command."; }
```

### Handling Auth

If your target cluster has basic auth enabled on it, you can supply those credentials to the tool via the CLI:

```shell
./gradlew DocumentsFromSnapshotMigration:run --args="\
  --snapshot-name reindex-from-snapshot \
  --s3-local-dir /tmp/s3_files \
  --s3-repo-uri s3://your-s3-uri \
  --s3-region us-fake-1 \
  --lucene-dir /tmp/lucene_files \
  --target-host http://hostname:9200 \
  --target-username <user> \
  --target-password <pass>" \
  || { exit_code=$?; [[ $exit_code -ne 3 ]] && echo "Command failed with exit code $exit_code. Consider rerunning the command."; }
```

### Limiting the amount of disk space used

In order to migrate documents from the snapshot, RFS first needs to have a local copy of the raw contents of the shard on disk.  If you're using a local snapshot, that's taken care of; but if your snapshot is in S3, RFS first downloads the portion of the snapshot related to that shard.  Either way, RFS then unpacks the raw shard stored in your snapshot into a viable Lucene Index in order to be able to pull the documents from it.  This means that, for the current design, you can estimate the total amount of disk space migrating a shard will require as ~2x the size of the data in the shard.

If you have some shards larger than the hosts running this tool can handle, you can set a maximum shard size as a CLI option.  In this case, the tool will reject shards larger than the specified size.  

Add `--max-shard-size-bytes 50000000000` to limit the size of the shards.

To see the default shard size, use the `--help` CLI option:

```shell
./gradlew DocumentsFromSnapshotMigration:run --args='--help'
```

## Arguments
| Argument                          | Description                                                                                                                                              |
|-----------------------------------|:---------------------------------------------------------------------------------------------------------------------------------------------------------|
| --snapshot-name                   | The name of the snapshot to migrate                                                                                                                      |
| --snapshot-local-dir              | The absolute path to the directory on local disk where the snapshot exists                                                                               |
| --s3-local-dir                    | The absolute path to the directory on local disk to download S3 files to                                                                                 |
| --s3-repo-uri                     | The S3 URI of the snapshot repo, like: s3://my-bucket/dir1/dir2                                                                                          |
| --s3-region                       | The AWS Region the S3 bucket is in, like: us-east-2                                                                                                      |
| --lucene-dir                      | The absolute path to the directory where we'll put the Lucene docs                                                                                       |
| --index-allowlist                 | Optional. List of index names to migrate (e.g. 'logs_2024_01, logs_2024_02'). Default: all non-system indices (e.g. those not starting with '.')         |
| --max-shard-size-bytes            | Optional. The maximum shard size, in bytes, to allow when performing the document migration. Default: 80 * 1024 * 1024 * 1024 (80 GB)                    |
| --initial-lease-duration          | Optional. The time that the first attempt to migrate a shard's documents should take. Default: PT10M                                                     |
| --otel-collector-endpoint         | Optional. Endpoint (host:port) for the OpenTelemetry Collector to which metrics logs should be forwarded. If not provided, metrics will not be forwarded |
| --target-host                     | The target host and port (e.g. http://localhost:9200)                                                                                                    |
| --target-username                 | The username for target cluster authentication                                                                                                           |
| --target-password                 | The password for target cluster authentication                                                                                                           |
| --target-aws-region               | The AWS region for the target cluster. Required if using SigV4 authentication                                                                            |
| --target-aws-service-signing-name | The AWS service signing name (e.g. 'es' for Amazon OpenSearch Service, 'aoss' for Amazon OpenSearch Serverless). Required if using SigV4 authentication  |
| --documents-size-per-bulk-request | Optional. The maximum aggregate document size to be used in bulk requests in bytes. Default: 10 MiB                                                      |
| --allowed-doc-exception-types     | Optional. Comma-separated list of document-level exception types to treat as successful operations. Enables idempotent migrations by allowing specific errors (e.g., 'version_conflict_engine_exception') to be treated as success. Default: none |

## Advanced Arguments

These arguments should be carefully considered before setting, can include experimental features, and can impact security posture of the solution. Tread with caution.

| Argument                    | Description                                                                                                          |
|-----------------------------|:---------------------------------------------------------------------------------------------------------------------|
| --disable-compression  | Flag to disable request compression for target cluster. Default: false                                               |
| --documents-per-bulk-request | The number of documents to be included within each bulk request sent. Default: no max (controlled by documents size) |
| --max-connections           | The maximum number of connections to simultaneously used to communicate to the target. Default: 10                   |
| --target-insecure           | Flag to allow untrusted SSL certificates for target cluster. Default: false                                          |

### Example: Handling Target Conflicts

If a transformation causes exceptions on the target, either from existing docs or more than once processing, you can allow conflicts to be treated as success:

```shell
./gradlew DocumentsFromSnapshotMigration:run --args="\
  --snapshot-name reindex-from-snapshot \
  --snapshot-local-dir /snapshot \
  --lucene-dir /tmp/lucene_files \
  --target-host http://hostname:9200 \
  --allowed-doc-exception-types version_conflict_engine_exception"
```

This will prevent the migration from retrying indefinitely when encountering documents that already exist on the target cluster. The migration will treat these conflicts as successful operations and proceed with the remaining documents.

### Supported Exception Types

Common exception types that can be allowlisted:
- `version_conflict_engine_exception` - Document already exists with a different version
- Other OpenSearch/Elasticsearch document-level exception types as needed

**Note:** Use this feature carefully. Allowlisting exceptions means those errors will be silently treated as success, which may mask legitimate issues if used incorrectly.
