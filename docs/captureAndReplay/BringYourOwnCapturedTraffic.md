# Bring-Your-Own Captured Traffic

## Status and authority

This document defines how an exported capture archive can be loaded and replayed without running a
live capture proxy.

The capture and replay protocol remains defined by:

- [`captureAndReplayArchitecture.md`](captureAndReplayArchitecture.md);
- [`proxyCaptureProtocol.md`](proxyCaptureProtocol.md); and
- [`replayerProcessingAndCommitArchitecture.md`](replayerProcessingAndCommitArchitecture.md).

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
- the proxy heartbeat-expiration interval `E`;
- the permitted Kafka `LogAppendTime` backward-movement allowance `S`;
- the explicit archive end mode, `finalized` or `range`; and
- integrity information that detects omitted, duplicated, reordered, or corrupted archive records.

Source Kafka offsets are retained as archive validation and diagnostic data. Importing creates a
new Kafka log, so it cannot recreate the physical source offsets or leader epochs. The replayer
uses the destination topic's offsets for processing and commits.

The live proxy's `trafficStreamFlushInterval` does not need to be replayed as configuration.
Its observable effect is the exact `TrafficStream` record boundaries already preserved by the
archive. The exporter and importer must not merge or split archived application records.

## Export completion

An export uses fixed per-partition start and end offsets. It is complete only when every record in
the declared ranges has been archived and the archive integrity data has been finalized. The
archive format defines the endpoint convention unambiguously.

Reaching the current end of a Kafka partition is not protocol completion evidence. The selected end
offsets define the export boundary.

The archive also declares one end mode:

- `finalized` means that all capture producers were stopped or quiesced before the fixed end
  offsets were selected. The archive is intended to represent the finite end of that capture.
- `range` means that the fixed offsets delimit an arbitrary range from a capture that may continue
  outside the archive.

The exporter must not infer `finalized` from a quiet topic, a timeout, or the current partition end.
The workflow or operator must certify it explicitly. The format has no implicit default.

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

`preserve` is the default. The importer writes the archived original `LogAppendTime` numeric value
as the destination record timestamp. The dedicated import topic must retain producer-supplied
timestamps rather than replacing them with the destination broker's current time.

Kafka reports the destination record's timestamp type according to the destination topic's timestamp
configuration. It cannot report the imported producer-supplied value as a newly assigned
`LogAppendTime`. The archive therefore preserves the source record's original timestamp type as
archive metadata, while replay in `preserve` mode treats the imported numeric timestamp as the
archived source broker time.

The replayer uses the archived `E` and `S` values and applies the normal broker-time expiration
rules. This mode assumes the source Kafka brokers satisfied the declared skew bound while the
capture was produced. The archived record timestamps also reproduce the deterministic
source-response boundary used by retry policy; the import broker's append time does not replace
them.

### `rebase-without-expiration`

`rebase-without-expiration` is an expert fallback for an archive whose original broker timestamps
cannot be preserved or trusted. The destination Kafka broker supplies the imported records'
timestamps.

In this mode, broker-time heartbeat expiration is disabled. Rebasing timestamps must never authorize
expiration under the original `E + S` proof, because the rebased values do not describe the source
capture timeline. The destination timestamps drive the source-response boundary used by retry
policy, but not source-run expiration.

For a `range` archive, choosing this mode also accepts that an archive ending with an incomplete
connection and no terminal `CloseObservation` may remain unresolved indefinitely. End of a range
is not completion evidence. A `finalized` archive uses the explicit partition-end rule below.

The selected timestamp mode is immutable for one imported capture and is passed explicitly to the
replayer. Proxy and replayer configuration must agree about the applicable protocol parameters.

## Replay behavior

After import, the replayer processes the destination Kafka records through the same protocol and
record-accounting paths used for live capture:

- the destination offsets control `Commit` and `Retain`;
- each raw record value retains its `CaptureRecord.payload` discriminator;
- record headers are preserved exactly, but they do not identify the capture-record payload type;
- `WriterPartitionHeartbeat` retains its protocol meaning;
- records may be replayed more than once after restart or reassignment; and
- for a `range` archive, end of archive does not complete unresolved HTTP assembly or other work.

A `finalized` archive has an explicit finite-input rule:

1. After replay intake processes the declared final Kafka record for a partition, the import
   workflow supplies one partition-end input for that partition.
2. The partition-end input is not a Kafka record. It has no Kafka offset or timestamp and cannot
   itself make a Kafka record committable.
3. It resolves unresolved source-response input for retry policy as unavailable.
4. It expires incomplete source-request and source-response assembly known at that boundary.
5. Target replay and tuple output for already-reconstituted requests continue normally.
6. Every Kafka record remains associated with its unfinished request, tuple, or cleanup work until
   that work reaches its ordinary final state.

The same finalized archive produces the same partition-end inputs after restart. A `range` archive
does not produce them; its final request, response, or retry wait may remain unresolved without
later records.

The importer preserves records exactly rather than recreating the proxy's periodic connection
flush schedule. A request or response split across archived records reconstructs from the same
ordered observations, and a complete response in the same archived record as request completion
has the same retry-policy meaning as it did in the live topic.

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
  archiveEndMode: z.enum(["finalized", "range"]),
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
2. Reject a changed archive identity, end mode, or timestamp mode for an existing load.
3. Validate the archive identity and structural metadata.
4. Stream, validate, and publish the destination records.
5. Record load statistics and archive identity.
6. Mark `CapturedTraffic` ready.
7. Permit the replayer to start.

The importer uses the workflow's configured Kafka authentication and its service account's S3
permissions.

## Required implementation changes

The current tools are not the hardened archive implementation:

- `../../migrationConsole/kafkaExport.sh` currently disables timestamp output.
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

1. An archive-codec round trip preserves partition, raw nullable key/value, original timestamp,
   original timestamp-type metadata, and duplicate ordered headers. Import in `preserve` mode keeps
   the archived numeric timestamp; it does not claim that Kafka reports the destination record with
   the source record's original timestamp type.
2. A multi-partition archive round trip preserves every partition's record order.
3. Import rejects unsupported protocol versions, missing partitions, gaps, duplicates, reordering,
   and integrity failures.
4. `preserve` retains archived timestamps and runs broker-time expiration using archived `E` and
   `S`.
5. `rebase-without-expiration` uses destination timestamps and cannot perform broker-time
   expiration.
6. Imported mixed records retain the same per-observation processing and whole-record commit
   behavior as live records.
7. Restart and partition reassignment demonstrate at-least-once behavior without introducing an
   archive-specific deduplication protocol.
8. A failed or ambiguous load does not automatically append a second copy to the same destination
   topic.
9. A full live-capture export/import/replay produces the same replay-visible protocol input as the
    original capture.
10. Import preserves every original `TrafficStream` boundary; it neither combines records created
    by separate periodic flushes nor splits one archived record.
11. Preserving those record boundaries and archived timestamps reproduces the same request record
    timestamp `B`, boundary-crossing record timestamp `R`, and source-response input selected for
    retry policy.
12. Export and import preserve the complete `CaptureRecord` envelope and each of its three recognized
    `payload` cases without relying on Kafka headers for record-type discrimination.
13. A `finalized` archive produces one deterministic partition-end input after each partition's
    declared final record. That input resolves retry waiting and incomplete assembly without
    bypassing target replay, tuple durability, or whole-record accounting.
14. A `range` archive produces no partition-end input. End of file alone does not resolve an open
    reconstruction or retry wait.
15. Import rejects a missing, unsupported, or changed `archiveEndMode`.
