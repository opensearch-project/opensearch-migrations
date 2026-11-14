# Replay Tuple Output

The traffic replayer writes one result tuple per replayed request, with the source request and response
alongside the target's. Tuples can be persisted to S3 as gzip-compressed JSON lines and inspected from
the Migration Console.

## Inspecting from the Migration Console

Run this from a Migration Console shell. When more than one replayer exists, add `--replayer <name>`
to choose one.

```
console tuples show --limit 5 --pretty
```

## Configuration

Set `tupleOutput.type` to `s3` to persist replay result tuples, or to `disabled` to use the configured
tuple logger without S3 persistence. Omitting `tupleOutput` also uses the configured tuple logger.

> **Upgrading:** the tuple output configuration under `replayerConfig` has changed. Replace
> `tupleS3Bucket`, `tupleS3Prefix`, `tupleS3Region`, and `tupleS3Endpoint` with a `tupleOutput` object.
> Set its `type` to `s3` and move the bucket, prefix, region, and endpoint into that object.
> The former flat fields are no longer accepted.

The destination is set under a replayer's `traffic.replayers.<name>.replayerConfig`:

```yaml
replayerConfig:
  tupleOutput:
    type: s3
    bucket: my-tuple-results-bucket
    region: us-east-1
```

or, to opt out:

```yaml
replayerConfig:
  tupleOutput:
    type: disabled
```

Changing the tuple output bucket on a running replay needs `workflow approve change`.

| Option                  | Default   | Description                                                       |
| ----------------------- | --------- | ----------------------------------------------------------------- |
| `tupleOutput.type`      | none      | Required when `tupleOutput` is set: `s3` or `disabled`.           |
| `tupleOutput.bucket`    | none      | Required when `type` is `s3`; bucket for replay result tuples.    |
| `tupleOutput.prefix`    | `tuples/` | S3 key prefix for tuple objects.                                  |
| `tupleOutput.region`    | none      | Required when `type` is `s3`; region for the bucket.              |
| `tupleOutput.endpoint`  | none      | HTTP(S) endpoint override (for example, LocalStack).              |
| `tupleMaxBufferSeconds` | `60`      | Max seconds before rotating and uploading a tuple file.           |
| `tupleMaxFileSizeMb`    | `256`     | Max uncompressed file size in MB before rotating to a new object. |
| `tupleMaxPerFile`       | `0`       | Max tuples per object. `0` disables the count limit.              |

The rotation settings belong directly to `replayerConfig`, alongside `tupleOutput`.
The bucket and region must be set explicitly for an S3 destination.

Tuple persistence supports S3. Native GCS and Azure Blob Storage destinations are not accepted.
