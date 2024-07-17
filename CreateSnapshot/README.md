# create-snapshot

## What is this tool?

This tool exposes the underlying Reindex-From-Snapshot (RFS) core library in a executable that will create a snapshot of a source cluster consistent with the expectations of RFS.  In brief, that means taking the snapshot with the global metadata included.  Currently, there are two may ways this tool can perform the snapshot:

* On-disk: The tool kick off a snapshot on the source cluster tell the cluster to store files on disk.  Keep in mind that the disk in question here is the disk of the nodes in the cluster.  This is typically used when your nodes share a filesystem, either because have have a network file system attached or they're all running on the same host.
* S3: The tool kick off a snapshot on the source cluster tell the cluster to store files to a specified S3 URI.

There's not much magic involved; the tool will reach out to the host specificed using the Elasticsearch REST API to register a new repository then create a snapshot.  This means you'll need a network path to the source cluster from where you're running the tool to the source cluster.

## How do use the tool?

You can kick off the tool using Gradle.

### On-Disk Snapshot

From the root directory of the repo, run a CLI command like so:

```
./gradlew CreateSnapshot:run --args='--snapshot-name reindex-from-snapshot --file-system-repo-path /snapshot --source-host http://hostname:9200'
```

In order for this to succeed, you must first configure your Elasticsearch source cluster register the directory you want to use as a snapshot repository.  See [the docs for ES 7.10 here](https://www.elastic.co/guide/en/elasticsearch/reference/7.10/snapshots-register-repository.html#snapshots-filesystem-repository) for an example of how to do this.

### S3 Snapshot

From the root directory of the repo, run a CLI command like so:

```
./gradlew CreateSnapshot:run --args='--snapshot-name reindex-from-snapshot --s3-repo-uri s3://your-s3-uri --s3-region us-fake-1 --source-host http://hostname:9200'
```

In order for this succeed, you'll need to make sure:
* You have valid AWS Credentials in your key ring (~/.aws/credentials) with permission to operate on the S3 URI specified
* The S3 URI currently exists, and is in the region you specify
* There are no other snapshots present in that S3 URI