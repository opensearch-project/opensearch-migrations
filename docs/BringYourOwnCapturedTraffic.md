# Bring-Your-Own Captured Traffic

## Status and authority

This document defines how an exported capture archive can be loaded and replayed without running a
live capture proxy.

The capture and replay protocol remains defined by:

- [`captureAndReplayArchitecture.md`](./captureAndReplayArchitecture.md);
- [`proxyCaptureProtocol.md`](./proxyCaptureProtocol.md); and
- [`replayerProcessingAndCommitArchitecture.md`](./replayerProcessingAndCommitArchitecture.md).

An imported archive is another representation of the Kafka application records produced by a
compatible capture proxy. It does not define a second replay protocol.

The existing migration-console `<key>|<base64(value)>` export and matching loader are legacy tools.
They do not preserve enough information for the hardened protocol described here.

## Compatibility

Bring-your-own captured traffic is supported only when the archive was produced by a capture proxy
that uses the same capture-protocol version as the importing replayer.

The archive records its protocol and build versions. The importer rejects an unsupported protocol
version before publishing any application records.

Compatibility across different capture-protocol versions is out of scope.

## Required fidelity

The exporter must preserve every field that can affect replay behavior. The archive contains:

- the capture-protocol version and producing build version;
- the source topic's partition count;
- a fixed start offset and end offset for every exported source partition;
- each record's source partition and source offset;
- the record's original timestamp and Kafka timestamp type;
- its raw nullable binary key;
- its raw nullable binary value;
- every Kafka header, preserving duplicate headers and header order;
- the order of records within each source partition;
- the proxy manifest-expiration interval `E`;
- the permitted Kafka `LogAppendTime` backward-movement allowance `S`; and
- integrity information that detects omitted, duplicated, reordered, or corrupted archive records.

The authoritative `writerNodeId` for records such as `NoMoreWrites` is carried in Kafka record
headers. Preserving only the protobuf value is therefore insufficient.

Source Kafka offsets are retained as archive validation and diagnostic data. Importing creates a
new Kafka log, so it cannot recreate the physical source offsets or leader epochs. The replayer
uses the destination topic's offsets for processing and commits.

## Export completion

An export uses fixed per-partition start and end offsets. It is complete only when every record in
the declared ranges has been archived and the archive integrity data has been finalized. The
archive format defines the endpoint convention unambiguously.

Reaching the current end of a Kafka partition is not protocol completion evidence. The selected end
offsets define the export boundary.

The exporter must not silently skip a record it cannot decode. The value is archived as raw bytes;
protocol decoding and validation can occur separately.

## Import behavior

The importer validates the archive structure, protocol version, partition mapping, and integrity
information. It must not make the captured traffic available to a replayer until the complete load
and all final integrity checks succeed.

For every archived source partition, the destination topic has the corresponding partition. The
importer publishes exactly one destination application record for each archived application record,
preserving:

- the archived partition;
- partition-local order;
- key nullability and bytes;
- value nullability and bytes;
- headers, including duplicates and order; and
- the timestamp selected by the configured timestamp mode.

Destination Kafka offsets are expected to differ from source offsets. The importer records enough
mapping information to diagnose an imported record from its archived source partition and offset.

The workflow exposes the resulting `CapturedTraffic` resource as ready only after the complete
archive has been successfully loaded and validated. A `CapturedTraffic` resource admits one
completed archive load. If a load has an ambiguous or failed outcome, the workflow does not claim
exactly-once publication and does not automatically start another load into the same topic. The
operator must reset the captured-traffic resource and destination topic before retrying.

## Timestamp modes

The import configuration contains:

```ts
timestampMode: z
    .enum(["preserve", "rebase-without-expiration"])
    .default("preserve")
    .optional()
```

### `preserve`

`preserve` is the default. The importer writes the archived original `LogAppendTime` as the
destination record timestamp. The dedicated import topic must retain producer-supplied timestamps
rather than replacing them with the destination broker's current time.

The replayer uses the archived `E` and `S` values and applies the normal broker-time expiration
rules. This mode assumes the source Kafka brokers satisfied the declared skew bound while the
capture was produced.

### `rebase-without-expiration`

`rebase-without-expiration` is an expert fallback for an archive whose original broker timestamps
cannot be preserved or trusted. The destination Kafka broker supplies the imported records'
timestamps.

In this mode, broker-time manifest expiration is disabled. Rebasing timestamps must never authorize
expiration under the original `E + S` proof, because the rebased values do not describe the source
capture timeline.

The selected timestamp mode is immutable for one imported capture and is passed explicitly to the
replayer. Proxy and replayer configuration must agree about the applicable protocol parameters.

## Replay behavior

After import, the replayer processes the destination Kafka records through the same protocol and
record-accounting paths used for live capture:

- the destination offsets control `Commit` and `Retain`;
- record headers retain their protocol meaning;
- manifests and `NoMoreWrites` retain their protocol meaning;
- records may be replayed more than once after restart or reassignment; and
- end of archive does not complete unresolved HTTP assembly or other work.

Retry policy for target HTTP requests is outside the scope of this architecture. Imported traffic
uses the same configured behavior as live traffic. Any retry remains part of the same target
exchange and does not become new Kafka work.

## Orchestration

The migration configuration may define an S3 captured-traffic source alongside live proxies:

```ts
export const S3_CAPTURED_TRAFFIC_SOURCE = z.object({
  s3Uri: z.string(),
  awsRegion: z.string(),
  kafka: z.string().optional(),
  kafkaTopic: z.string().optional(),
  sourceLabel: z.string(),
  timestampMode: z
      .enum(["preserve", "rebase-without-expiration"])
      .default("preserve")
      .optional(),
});
```

A replayer refers to a captured-traffic source without depending on whether a proxy or an importer
created it. Names for live and imported sources must not collide.

For an imported source:

1. Reconcile the `CapturedTraffic` resource and destination topic.
2. Reject a changed archive identity or timestamp mode for an existing load.
3. Validate the archive identity and structural metadata.
4. Stream, validate, and publish the destination records.
5. Record load statistics and archive identity.
6. Mark `CapturedTraffic` ready.
7. Permit the replayer to start.

The importer uses the workflow's configured Kafka authentication and its service account's S3
permissions.

## Required implementation changes

The current tools are not the hardened archive implementation:

- `migrationConsole/kafkaExport.sh` currently disables timestamp output.
- `Base64Formatter` currently exports a UTF-8 key and base64 value only.
- `KafkaLoader` currently reconstructs a record without its source partition, original timestamp,
  or headers.

The exporter and importer must be updated or replaced with a versioned binary-safe archive codec.
The existing simple format may remain available as a legacy diagnostic export, but it must not be
described as suitable for exact protocol replay.

Diagnostic labels such as `minEpoch` and `maxEpoch` in existing dump output describe observation
time ranges. They are not Kafka consumer-group generations, producer fencing epochs, or part of the
capture coordination protocol.

## Verification

The following tests are required:

1. A record round trip preserves partition, raw nullable key/value, timestamp, timestamp type, and
   duplicate ordered headers.
2. A multi-partition archive round trip preserves every partition's record order.
3. Import rejects unsupported protocol versions, missing partitions, gaps, duplicates, reordering,
   and integrity failures.
4. `preserve` retains archived timestamps and runs broker-time expiration using archived `E` and
   `S`.
5. `rebase-without-expiration` uses destination timestamps and cannot perform broker-time
   expiration.
6. A header-carried `writerNodeId`, including for `NoMoreWrites`, survives export and import.
7. Imported mixed records retain the same per-observation processing and whole-record commit
   behavior as live records.
8. Restart and partition reassignment demonstrate at-least-once behavior without introducing an
   archive-specific deduplication protocol.
9. A failed or ambiguous load does not automatically append a second copy to the same destination
   topic.
10. A full live-capture export/import/replay produces the same replay-visible protocol input as the
    original capture.
