# Documents from Snapshot Migration (RFS)

## What is this tool?

This tool exposes the underlying Reindex-From-Snapshot (RFS) core library in an executable that will migrate the documents from a specified snapshot to a target cluster.  Very briefly, the application will parse the contents of the specified snapshot, pick a shard from the snapshot, then extract the documents from the shard and move them to the target cluster.  After moving the contents of the shard, the application exits.  It is intended to be run iteratively until there are no more shards left to migrate.  The application keeps track of which shards have been moved by storing metadata in a special index on a coordinator cluster.  By default (when `--coordinator-host` is not provided), the target cluster is used for coordination.  When `--coordinator-host` is specified, a separate cluster handles coordination, isolating that traffic from the target.  This metadata enables many instances of the application to run simultaneously, determining which instance migrates which shard, reducing duplicated work, and retrying failures.  You can read a lot more about this in [the RFS Design Doc](../RFS/docs/DESIGN.md).

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
| --otel-trace-collector-endpoint   | Optional. Endpoint (host:port or URL) for the OpenTelemetry Collector to which traces should be forwarded. Omit to disable trace export                  |
| --otel-metrics-collector-endpoint | Optional. Endpoint (host:port or URL) for the OpenTelemetry Collector to which metrics should be forwarded. Omit to disable metric export                |
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
| --coordinator-host          | The coordinator cluster host and port (e.g. http://localhost:9200). When provided, work coordination uses this cluster instead of the target cluster |
| --coordinator-username      | Username for coordinator cluster basic authentication                                                                |
| --coordinator-password      | Password for coordinator cluster basic authentication                                                                |
| --coordinator-insecure      | Flag to allow untrusted SSL certificates for coordinator cluster. Default: false                                     |
| --coordinator-aws-region    | AWS region for coordinator cluster. Required if using SigV4 authentication for coordinator                           |
| --coordinator-aws-service-signing-name | AWS service signing name for coordinator (e.g. 'es' for Amazon OpenSearch Service). Required if using SigV4 for coordinator |
| --coordinator-retry-max-retries | Maximum number of retries when marking work items as completed on the coordinator. Default: 7                      |
| --coordinator-retry-initial-delay-ms | Initial delay in milliseconds for coordinator completion retries (doubles each attempt). Default: 1000       |
| --coordinator-retry-max-delay-ms | Maximum delay in milliseconds for any single coordinator completion retry. Default: 64000                       |

#### Coordinator Retry Behavior

These settings apply to coordinator work-item completion retries only and do not change target bulk indexing retry behavior. They are intended for transient coordinator outages such as pod restarts or evictions.

When `--coordinator-host` is provided, the tool uses that cluster for coordination. When omitted, the target cluster is used.

When RFS finishes migrating documents for a shard, it marks the work item as completed on the coordinator cluster. If the coordinator is temporarily unavailable during this step, RFS retries with an exponential backoff strategy. The retry parameters (`--coordinator-retry-max-retries`, `--coordinator-retry-initial-delay-ms`, `--coordinator-retry-max-delay-ms`) can be tuned for environments with longer coordinator restart times or for faster failure detection.

#### Work Coordination Mode (Orchestration)

When run via the migration workflow, the `useTargetClusterForWorkCoordination` option in `documentBackfillConfig` controls whether the orchestration deploys a dedicated coordinator cluster and passes `--coordinator-host` to this tool automatically.

| Value | Behavior                                                                                                         | Default |
|-------|------------------------------------------------------------------------------------------------------------------|---------|
| `false` | Deploys a dedicated single-node OpenSearch coordinator cluster, managed automatically for the migration lifetime. The orchestration passes `--coordinator-host` to this tool and tears down the coordinator on completion. | ✔️ |
| `true` | Uses the target OpenSearch cluster for work coordination (no `--coordinator-host` is passed).                    | |

The dedicated coordinator (`false`) isolates coordination traffic from the target cluster,
preventing work-coordination writes from competing with bulk indexing. Set to `true` when
the overhead is acceptable on the target cluster.

See the [orchestrationSpecs schema](../orchestrationSpecs/packages/schemas/src/userSchemas.ts) for the full configuration reference.

#### Early Checkpoint Before Lease Expiry

Workers checkpoint in the lease-timeout path before hard lease expiry.

Lease-timeout trigger:
- `checkpointTriggerTime = max(leaseDuration * 0.75, leaseDuration - PT4M30S)`

Behavior at trigger time:
- Cancel active shard migration work in the current process.
- Capture the latest progress cursor.
- Persist handoff metadata on the coordinator (`successor_items` + successor work item creation + parent completion).
- Exit the worker so another worker can reclaim and continue remaining work.

Why this exists:
- Reduces the risk of losing in-memory progress when coordinator connectivity drops near lease expiry.
- Provides extra time for coordinator retry/backoff to succeed before the hard lease boundary.
- Retry backoff waits in the lease-timeout path are deadline-bounded by the original lease expiry. A retry will not begin a new backoff sleep if it would exceed the lease boundary.

Dedicated coordinator outage example (PT60S lease):
- `t=0s`: worker starts migrating shard docs
- `t=40s`: coordinator becomes unavailable
- `t=45s`: early checkpoint/cancel trigger fires (`max(60s*0.75, 60s-270s)=45s`)
- `t=45s+`: coordinator retry logic attempts to persist handoff metadata
- `t=48s`: coordinator recovers; retry can succeed and persist `successor_items`
- Next worker reclaims successor work item and continues remaining docs

### Exit Code Contract

Common RFS worker outcomes:

| Exit code | Meaning |
|-----------|---------|
| `0` | Worker completed its current unit of work and exited normally. |
| `2` | Worker exited from lease-timeout/handoff flow (`PROCESS_TIMED_OUT_EXIT_CODE`). This is expected in lease handoff scenarios. |
| `3` | No work left (`NO_WORK_LEFT_EXIT_CODE`). |

In orchestrated runs (Argo/ECS/K8s), treat `2` as a recoverable handoff signal rather than terminal data-loss failure.

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

The migration will treat these conflicts as successful operations instead of recording them as non-retryable document failures.

### Document versioning

RFS always reads the Lucene `_version` for indexed documents. When a version and
source ID are available, RFS includes that `version` and `version_type: "external"`
in the bulk action by default. A document at version 7 in the snapshot is indexed
at version 7 on the target. No extraction flag or transformation is needed; the
default path still sends the original source bytes without parsing the body into
JavaScript.

External versioning accepts only a version greater than the target's current
version. Equal-version retries and attempts to replace newer target versions
produce `version_conflict_engine_exception`. These follow the normal non-retryable
document failure path, including failed-document records when configured.
To explicitly treat conflicts as successful operations, use
`--allowed-doc-exception-types version_conflict_engine_exception`. No versioning
mode automatically suppresses these failures.

`version_type` is a per-request concurrency policy, not persistent document
metadata. The snapshot's `_version` cannot reveal whether source writes used
`internal`, `external`, or `external_gte`. RFS preserves the number regardless of
how it was assigned. Ordinary ingestion after backfill continues to increment
the target version: a normal write after version 7 becomes version 8.

Documents without a snapshot version use internal versioning. When RFS uses
server-generated IDs, it omits both the source ID and the explicit version.
Delete operations do not inherit snapshot versions: a delta snapshot contains
the previous document's version, not the deletion's version. External versioning
requires index operations; transformations producing create-only operations
must remove the explicit version. The bundled data-stream backing-index
transformation does this when it selects `op_type: "create"`.

The optional built-in Java and JavaScript modifiers support all three policies:

| `versionType` | Behavior |
|---|---|
| `internal` | Remove `version` and `version_type`; the target assigns or increments its own version. |
| `external` (default) | Retain the selected write version or use a configured source field; only higher versions overwrite. |
| `external_gte` | Retain the selected write version or use a configured source field; equal or higher versions overwrite. |

Unknown version types are rejected. The modifier is not applied by default;
the native RFS write path already preserves snapshot versions using `external`.
Changing only `versionType` preserves `operation.version`, including a value
selected by an earlier transformation. If that value is absent, the modifier
falls back to `source_metadata._version`, allowing internal mode to be followed
by an external mode.

#### Native Java versioning

Use the bundled Java provider to change the version policy without converting
document bodies to Maps. It is available in the standard image and accepts an
inline startup argument:

```shell
--doc-transformer-config '[{"BulkVersioningTransformerProvider":{"versionType":"external_gte"}}]'
```

Use `"internal"` to remove explicit versioning, or `"external"` for strict
greater-than versioning. A chain composed entirely of native
`BulkOperationTransformer` implementations retains the source bytes through
metadata changes, serialization, and retries. Adding a JavaScript or other JSON
transformer uses the existing JSON path, in the configured order.

The Java provider also accepts `versionField`, either a field name or a list of
nested field names. Selecting an application field parses the body; using the
snapshot version does not. Java preserves integer values through `Long.MAX_VALUE`
without JavaScript's numeric precision limit. Floating-point application values
are rejected.

The bundled [externalVersioning.js](../transformation/standardJavascriptTransforms/src/externalVersioning.js)
remains available through `JsonJSTransformerProvider`, with its options inside
`bindingsObject`. The examples below show that configuration.

#### Opting out with internal versioning

Set `versionType` to `internal` to remove `version` and `version_type` from the
action, retaining the ID, routing, body, and source metadata. This mode does not
require a snapshot version or look up an application version field.

Save this configuration as `internal-versioning.json`:

```json
[
  {
    "JsonJSTransformerProvider": {
      "initializationResourcePath": "js/externalVersioning.js",
      "bindingsObject": {
        "versionType": "internal"
      }
    }
  }
]
```

Apply it with:

```shell
--doc-transformer-config-file /path/to/internal-versioning.json
```

New target documents then start at version 1. Writes to existing target documents
increment their current version; opting out does not reset existing counters.

#### Allowing equal-version rewrites

To overwrite documents whose target version equals the snapshot version, use
the same modifier with `versionType` set to `external_gte`:

```json
[
  {
    "JsonJSTransformerProvider": {
      "initializationResourcePath": "js/externalVersioning.js",
      "bindingsObject": {
        "versionType": "external_gte"
      }
    }
  }
]
```

This allows rerunning a transformation at the same version, including replacing
different target content at that version. Older versions still cannot overwrite
newer ones; those conflicts remain failures unless explicitly allowlisted.
A custom JavaScript transformation can make the same policy change
for an index operation simply by assigning:

```javascript
document.operation.version_type = "external_gte";
```

#### Application version fields and JavaScript precision

In either external mode, the modifier can replace the selected write version with a
field from `_source`. Add `"versionField": "ext_version"` to its
`bindingsObject`. A string names a literal field, including any dots in its name.
For a nested field, use an array such as
`"versionField": ["metadata", "revision"]`. Missing or invalid values fail the
transformation. The source body is preserved. `versionField` is ignored in
internal mode.

RFS exposes the original version as `source_metadata._version` and the default
write version as `operation.version`. Both are decimal strings in transformation
input, preserving all 64 bits. Custom transformations can keep, replace, or
remove the action's version; the original source metadata remains available.
The serializer omits `source_metadata` from bulk requests and converts the
action version to a Java `Long`, writing a JSON integer on the wire.

Versions must be non-negative integers up to `9223372036854775807`. Quoted values
have the same limit: `"92233720368547758070"` is rejected. JavaScript numbers
supplied through `versionField` are accepted through `9007199254740991`
(`2^53 - 1`); store larger application versions as decimal strings. Preserve
these strings inside JavaScript instead of converting them to `Number`.

At `Long.MAX_VALUE`, there is no higher valid version and an internal increment
can overflow. For such documents, consider opting out when populating a new
target if subsequent ingestion needs internal increments.

Version preservation does not order independent source and target write
histories. An old snapshot document at version 100 can overwrite a newly
ingested target document at version 1. Plan backfill and live-ingestion ordering
accordingly.

### Supported Exception Types

Common exception types that can be allowlisted:
- `version_conflict_engine_exception` - Document already exists with a different version
- Other OpenSearch/Elasticsearch document-level exception types as needed

**Note:** Use this feature carefully. Allowlisting exceptions means those errors will be silently treated as success, which may mask legitimate issues if used incorrectly.
