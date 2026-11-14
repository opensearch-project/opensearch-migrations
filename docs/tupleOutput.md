# Replay tuple output

Configure persisted replay result tuples under `traffic.replayers.<name>.replayerConfig.tupleOutput`.
The destination uses the same `type: s3` or `type: disabled` choice as the failed-document stream.
S3 is the supported object store; Azure Blob Storage and GCS destinations are not accepted.

```yaml
replayerConfig:
  tupleOutput:
    type: s3
    bucket: my-tuple-results
    prefix: tuples/
    region: us-east-1
    # endpoint: http://localhost:4566
  tupleMaxBufferSeconds: 60
  tupleMaxFileSizeMb: 256
  tupleMaxPerFile: 0
```

The bucket and region are required when `type` is `s3`. The prefix defaults to `tuples/`.
The optional endpoint accepts an HTTP(S) URL. Tuple rotation settings belong to `replayerConfig`.

Omit `tupleOutput`, or select `tupleOutput: {type: disabled}`, to use the configured tuple logger
without S3 persistence. This differs from `failedDocumentStream`, where an explicit choice is required.

Replace the former flat user fields as follows:

| Former field | Replacement |
|---|---|
| `tupleS3Bucket` | `tupleOutput.bucket` with `tupleOutput.type: s3` |
| `tupleS3Region` | `tupleOutput.region` |
| `tupleS3Prefix` | `tupleOutput.prefix` |
| `tupleS3Endpoint` | `tupleOutput.endpoint` |

The config processor resolves the destination into the existing flat workflow, TrafficReplay CRD,
and replayer options. Direct replayer CLI flags and console tuple inspection keep their existing
interfaces. Destination changes retain the existing workflow approval requirements.
