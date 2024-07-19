# metadata-migration

## What is this tool?

This tool exposes the underlying Reindex-From-Snapshot (RFS) core library in a executable that will migrate the templates and indices in a specified snapshot of a source cluster to a target cluster.  In brief, it parses the contents of the snapshot to extract the settings/configuration of the templates and indices in the snapshot and then migrates those to the target cluster.  The snapshot can either be local on disk or in S3.  The user can apply allowlists to filter which templates/indices are migrated.  If a template or index of the same name already exists on the target cluster, this tool will not overwrite the existing one on the target.  

The tool will also apply some basic transformations to the template and index settings in an attempt to handle upgrades between version-specific behavior.  Further work is planned to flesh out this process; see [this design doc](./docs/DESIGN.md).

## How to use the tool

You can kick off the locally tool using Gradle.

### S3 Snapshot

From the root directory of the repo, run a CLI command like so:

```shell
./gradlew MetadataMigration:run --args='--snapshot-name reindex-from-snapshot --s3-local-dir /tmp/s3_files --s3-repo-uri s3://your-s3-uri --s3-region us-fake-1 --target-host http://hostname:9200'
```

In order for this succeed, you'll need to make sure you have valid AWS Credentials in your key ring (~/.aws/credentials) with permission to operate on the S3 URI specified.

### On-Disk Snapshot

From the root directory of the repo, run a CLI command like so:

```shell
./gradlew MetadataMigration:run --args='--snapshot-name reindex-from-snapshot --file-system-repo-path /snapshot --s3-region us-fake-1 --target-host http://hostname:9200'
```

### Handling Auth

If your target cluster has basic auth enabled on it, you can supply those credentials to the tool via the CLI:

```shell
./gradlew MetadataMigration:run --args='--snapshot-name reindex-from-snapshot --s3-local-dir /tmp/s3_files --s3-repo-uri s3://your-s3-uri --s3-region us-fake-1 --target-host http://hostname:9200 --target-username <user> --target-password <pass>'
```

### Allowlisting the templates and indices to migrate

By default, the tool has an empty allowlist for templates, meaning none will be migrated.  In contrast, the default allowlist for indices is open, meaning all non-system indices (those not prefixed with `.`) will be migrated.  You can tweak these allowlists with a comma-separated list of items you specifically with to migrate.  If you specify an custom allowlist for the templates or indices, the default allowlist is disregarded and **only** the items you have in your allowlist will be moved.

```shell
./gradlew MetadataMigration:run --args='--snapshot-name reindex-from-snapshot --s3-local-dir /tmp/s3_files --s3-repo-uri s3://your-s3-uri --s3-region us-fake-1 --target-host http://hostname:9200 --index-allowlist Index1,.my_system_index,logs-2023 --index-template-allowlist logs_template --component-template-allowlist component2,component7'
```

In the above example, the tool will migrate the following items from the snapshot per the allowlist:
* The indices `Index1`, `.my_system_index`, and `logs-2023`
* The index template `logs_template`
* The component templates `component2` and `component7`