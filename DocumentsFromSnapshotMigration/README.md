# create-snapshot

## What is this tool?

This tool exposes the underlying Reindex-From-Snapshot (RFS) core library in a executable that will migrate the documents from a specified snapshot to a target cluster.  Very briefly, the application will parse the contents of the specified snapshot, pick a shard from the snapshot, then extract the documents from the shard and move them to the target cluster.  After moving the contents of the shard, the application exits.  It is intended to be run iteratively until there are no more shards left to migrate.  The application keeps track of which shards have been moved by storing metadata in a special index on the target cluster.  The metadata serves as a point of coordination when many instances of this application are running simultaneously to do things like determining which instance migrates which shard, reducing the amount of duplicated work, retrying failures, etc.  You can read a lot more about this in [the RFS Design Doc](../RFS/docs/DESIGN.md).

The snapshot the application extracts the documents from can be local or in S3.  You'll need network access to the target cluster because the application uses the standard REST API on the cluster to ingest the extracted documents.

## How to use the tool

You can kick off the locally tool using Gradle.

### S3 Snapshot

From the root directory of the repo, run a CLI command like so:

```
./gradlew DocumentsFromSnapshotMigration:run --args='--snapshot-name reindex-from-snapshot --s3-local-dir /tmp/s3_files --s3-repo-uri s3://your-s3-uri --s3-region us-fake-1 --lucene-dir /tmp/lucene_files --target-host http://hostname:9200'
```

In order for this succeed, you'll need to make sure you have valid AWS Credentials in your key ring (~/.aws/credentials) with permission to operate on the S3 URI specified

### On-Disk Snapshot

From the root directory of the repo, run a CLI command like so:

```
./gradlew DocumentsFromSnapshotMigration:run --args='--snapshot-name reindex-from-snapshot --snapshot-local-dir /snapshot --lucene-dir /tmp/lucene_files --target-host http://hostname:9200'
```

### Handling Auth

If your target cluster has basic auth enabled on it, you can supply those credentials to the tool via the CLI:

```
./gradlew DocumentsFromSnapshotMigration:run --args='--snapshot-name reindex-from-snapshot --s3-local-dir /tmp/s3_files --s3-repo-uri s3://your-s3-uri --s3-region us-fake-1 --lucene-dir /tmp/lucene_files --target-host http://hostname:9200 --target-username <user> --target-password <pass>'
```

### Limiting the amount of disk space used

In order to migrate documents from the snapshot, RFS first needs to have a local copy of the raw contents of the shard on disk.  If you're using a local snapshot, that's taken care of; but if your snapshot is in S3, RFS first downloads the portion of the snapshot related to that shard.  Either way, RFS then unpacks the raw shard stored in your snapshot into a viable Lucene Index in order to be able to pull the documents from it.  This means that, for the current design, you can estimate the total amount of disk space migrating a shard will require as ~2x the size of the data in the shard.

If you have some shards larger than the hosts running this tool can handle, you can set a maximum shard size as a CLI option.  In this case, the tool will reject shards larger than the specified size.  

```
./gradlew DocumentsFromSnapshotMigration:run --args='--snapshot-name reindex-from-snapshot --s3-local-dir /tmp/s3_files --s3-repo-uri s3://your-s3-uri --s3-region us-fake-1 --lucene-dir /tmp/lucene_files --target-host http://hostname:9200 --max-shard-size-bytes 50000000000'
```

To see the default shard size, use the `--help` CLI option:

```
./gradlew DocumentsFromSnapshotMigration:run --args='--help'
```