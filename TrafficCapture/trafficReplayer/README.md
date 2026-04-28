# HTTP Traffic Replayer

This package consumes streams of IP packets that were previously recorded and replays the requests to another HTTP
server, recording the packet traffic of the new interactions for future analysis.

## Overview

The replayer will consume an InputStream of protobuf
encoded [TrafficStream](../captureProtobufs/src/main/proto/TrafficCaptureStream.proto) objects.

Currently, these TrafficStream objects are ingested via stdin and are reconstructed into entire traffic channels. This
involves some buffering for those connections whose contents are divided into a number of TrafficStream objects.
Read and write observations are extracted from TrafficStream objects into source requests and source responses.
The [CapturedTrafficToHttpTransactionAccumulator](src/main/java/org/opensearch/migrations/replay/CapturedTrafficToHttpTransactionAccumulator.java)
takes full requests (as defined by the data, not necessarily by the HTTP format) and sends them to
an [IPacketConsumer](src/main/java/org/opensearch/migrations/replay/datahandlers/IPacketConsumer.java). The packet handler is
responsible for doing
any transformation of the request and sending it to the target server. It is also responsible for aggregating the HTTP
response from the server and returning that as a CompletableFuture via finalizeRequest().

Once the response is acquired, the full response and the recorded request and response for the source interaction, plus
other pertinent information is sent to stdout.

## The Netty Request Transformation Pipeline

There are two implementations of
IPacketToHttpHandler, [NettyPacketToHttpConsumer](../trafficReplayer/src/main/java/org/opensearch/migrations/replay/datahandlers/NettyPacketToHttpConsumer.java),
which will send packets to the target server
and [HttpJsonTransformingConsumer](./src/main/java/org/opensearch/migrations/replay/datahandlers/http/HttpJsonTransformingConsumer.java)
that is capable of transforming the message as per directives passed to the JsonTransformer.

Examples of transformations that the HttpJsonTransfomer needs to run include mapping the host header to match the new
target URI, as mentioned above; other headers, such as 'transfer-encoding' or 'content-encoding'; or those that modify
the payload of the request as well. To manage all of these transformations, the HttpJsonTransformer utilizes a netty
EmbeddedChannel that all captured packets are routed into. The EmbeddedChannel has ChannelHandlers to decode the HTTP
request and to convert it into a JSON format that includes the URI, headers, and contents. A transformation is then run
over the constructed JSON document.

Since payloads can be arbitrarily complex (compression, chunking), and may not be subject to any changes via the
transformation rules, the HttpJsonTransformer creates the channel pipeline *only* to parse the HTTP headers. The
transformation is run on this partial, in-construction
[HttpJsonMessageWithFaultablePayload](./src/main/java/org/opensearch/migrations/replay/datahandlers/http/HttpJsonMessageWithFaultingPayload.java)
message. When the transformation (or any other code), attempts to access the payload contents, it will throw a
[PayloadNotLoadedException](./src/main/java/org/opensearch/migrations/replay/datahandlers/PayloadNotLoadedException.java)
exception. That exception triggers the HttpJsonTransformer to add channel handlers to the pipeline to parse the HTTP
content stream into JSON, transform it, and to repackage it as the HTTP headers indicate, observing the content-encoding
(gzip, etc) and transfer-encoding (chunked)/content-length values. Fixed length streams will be used by default, with
the content-length being reset to the appropriate value.

The approach above means that there are multiple pipelines that can be constructed for each given message. Each
HttpTransformer is for an independent request, and each has its own EmbeddedChannel and pipeline. All messages will
have their HTTP headers parsed and an initial 'test' transform will always be run. If the test transform succeeds (no
exception was thrown), then the resultant pipeline will simply pass the contents as they were received without further
parsing them. If the headers have indicated only a change to the content encoding and not to the payload contents,
handlers will be added to the pipeline to normalize the payload into a stream and then to repack them as appropriate,
avoiding the JSON marshalling work.

Since handlers in a netty pipeline don't obviously show the types that they consume and produce or how they fit
together, the pipeline for the HttpJsonTransformer's EmbeddedChannel is managed through
(
RequestPipelineOrchestrator)[TrafficCapture/trafficReplayer/src/main/java/org/opensearch/migrations/replay/datahandlers/http/RequestPipelineOrchestrator.java],
which has comments throughout it to indicate how data percolates and is converted through the pipeline.

## Handlers

Except for the conversions around JSON payloads, all the other handlers (compression,
chunked, and JSON parsing/serialization), use streaming data models via mostly custom handlers. This should minimize the
memory load (working set size, cache misses, etc). However, attempts have not yet been made to reduce the number of
allocations. Those optimization may not have extremely high value, especially when JSON parsing will need to create
multitudes more objects.

Netty has a vast array of HTTP utilities, but this code base eschews using them in several places because it is simpler
and clearer to work with our HTTP JSON model, which is the interface that request customization will use - for payload
AND header/URI transformations, and to maintain greater control over the exact contents that is eventually sent to the
target URI.

## Transformations

Transformations are performed via a simple interface defined by
[IJsonTransformer](../../transformation/transformationPlugins/jsonMessageTransformers/jsonMessageTransformerInterface/src/main/java/org/opensearch/migrations/transform/IJsonTransformer.java) ('transformer').  They are loaded dynamically and are designed to allow for easy extension
of the TrafficReplayer to support a diverse set of needs.

The input to the transformer is an HTTP message represented as a json-like `Map<String,Object>` with
top-level key-value pairs defined in
[JsonKeysForHttpMessage.java](../../transformation/transformationPlugins/jsonMessageTransformers/jsonMessageTransformerInterface/src/main/java/org/opensearch/migrations/transform/JsonKeysForHttpMessage.java).
Bodies that are json-formatted will be accessible via the path `payload.inlinedJsonBody` and they will be accessible 
as a fully-parsed Map.  Newline-delimited json (ndjson) sequences will be accessible via 
`payload.inlinedJsonSequenceBodies` as a List of json Maps.  These two payload entries are mutually exclusive.
Any additional bytes that follow a json object (or all of the bytes if there wasn't a json object at all) will
be available as a ByteBuf in `payload.inlinedBinaryBody`.  

Transformers have the option to rewrite none, or any of the keys and values within the original message.  
The transformer can return either the original message or a completely new message.  Notice that one json payload
could be broken into multiple ndjson entries or vice-versa by changing the payload key and supplying an appropriately
typed object as its value (e.g. a single Map or a List of Maps respectively for `inlinedJsonBody` and
`inlinedJsonSequenceBodies`).
Transformers may be used simultaneously from concurrent threads over the lifetime of the replayer.  However, 
a message will only be processed by one transformer at a time.

Transformer implementations are loaded via [Java's ServiceLoader](https://docs.oracle.com/javase/8/docs/api/java/util/ServiceLoader.html)
by loading a jarfile that implements the [IJsonTransformerProvider](../../transformation/transformationPlugins/jsonMessageTransformers/jsonMessageTransformerInterface/src/main/java/org/opensearch/migrations/transform/IJsonTransformerProvider.java).
That jarfile will be loaded by specifying the provider jarfile (and any of its dependencies) in the classpath.
For the ServiceLoader to load the IJsonTransformerProvider, the provided jarfile needs
to supply a _provider-configuration_ file (`META-INF/services/org.opensearch.migrations.transform.IJsonTransformerProvider`)
with the fully qualified class name of the IJsonTransformerProvider implementation that should be loaded.  The contents
of that file must be in plain text with a single class on a line.

The user must specify transformers to use and their configuration to the TrafficReplayer via the `--transformer-config` 
argument.  If only one transformer is to be used and it doesn't require additional configuration, specifying JUST the
classname will cause that single transformer to be used.  Otherwise, the user must specify `--transformer-config` as
a json formatted list of maps.  The contents may also be specified in a file that can be read from 
`--transformer-config-file` (which is mutually exclusive with `--transformer-config`).  The order of the list of 
transformers and configs will define the order that the transformations are run.  Within each map is a single 
key-value pair whose name must match the getName() of the IJsonTransformerProvider that the user is attempting to use.
The name is defined by the `IJsonTransformerProvider::getName()`, which unless overridden is the classname 
(e.g. 'JsonJoltTransformerProvider').  The value corresponding to that key is then passed to instantiate an 
IJsonTransformer object.

The jsonMessageTransformerInterface package includes [JsonCompositeTransformer.java]
(../../transformation/transformationPlugins/jsonMessageTransformers/jsonMessageTransformerInterface/src/main/java/org/opensearch/migrations/transform/JsonCompositeTransformer.java),
which runs configured transformers in serial.  
That composite transformer is also utilized by the TrafficReplayer to combine the 
list of loaded transformations with a transformer to rewrite the 'Host' header.  That host transformation changes the 
host header of every HTTP message to use the target domain-name rather than the source's.  That will be run after
all loaded/specified transformations.

Currently, there are multiple, nascent implementations included in the repository.  The 
[JsonJMESPathTransformerProvider](../../transformation/transformationPlugins/jsonMessageTransformers/jsonJMESPathMessageTransformerProvider) 
package uses JMESPath expressions to transform requests and the
[jsonJoltMessageTransformerProvider](../../transformation/transformationPlugins/jsonMessageTransformers/jsonJoltMessageTransformerProvider)
package uses [JOLT](https://github.com/bazaarvoice/jolt) to perform transforms.  The JMESPathTransformer takes an inlined script as shown below.
The Jolt transformer can be configured to apply a full script or to use a "canned" transform whose script is 
already included with the library.  

The following is an examples that uses a JMESPath transformation to excise "oldType", followed by a built-in
transform to add GZIP encoding and another to apply a new header would be configured with the following.

```
[
{"JsonJMESPathTransformerProvider": { "script": 
    "{\"method\": method,\"URI\": URI,\"headers\":headers,\"payload\":{\"inlinedJsonBody\":{\"mappings\": payload.inlinedJsonBody.mappings.oldType}}}"}},
{"JsonJoltTransformerProvider": { "canned": "ADD_GZIP" }},
{"JsonJoltTransformerProvider":  {"script": 
    { "operation": "modify-overwrite-beta", "spec":       { "headers": {"newHeader": "newValue"}}}}}]
```

To run only one transformer without any configuration, the `--transformer-config` argument can simply 
be set to the name of the transformer (e.g. 'TypeMappingSanitizationTransformerProvider', 
without quotes or any json surrounding it).

The user can also specify a file to read the transformations from using the `--transformer-config-file`.  Users can
also pass the script as an argument via `--transformer-config-base64`.  Each of the `transformer-config` options
is mutually exclusive.

Some simple transformations are included to change headers to add compression or to force an HTTP message payload to 
be chunked.  Another transformer, [TypeMappingSanitizationTransformer.java](../../transformation/transformationPlugins/jsonMessageTransformers/jsonTypeMappingsSanitizationTransformer/src/main/java/org/opensearch/migrations/transform/TypeMappingsSanitizationTransformer.java),
is a work-in-progress to excise type mapping references from URIs and message payloads since versions of OpenSource
greater than 2.3 do not support them.

When these transformations are enabled, they will be run on each message.  If a transformation should only be run
for certain request types, that is the responsibility of the transformer implementation.  While a transformer doesn't
have the ability to return control information to a next transformer, it does send back and entire HTTP message that 
can be augmented however it may choose.


## Authorization Header for Replayed Requests

There is a level of precedence that will determine which or if any Auth header should be added to outgoing Replayer requests, which is listed below.
1. If the user provides an explicit auth option to the Replayer, such as `--target-username`/`--target-password` for basic auth, `--sigv4-auth-header-service-region` for SigV4, or `--remove-auth-header` to strip auth, this mechanism will be used for the auth header of outgoing requests. The options can be found as Parameters [here](src/main/java/org/opensearch/migrations/replay/TrafficReplayer.java)
2. If the user provides no auth header option and incoming captured requests have an auth header, this auth header will try to be reused for outgoing requests. **Note**: Reusing existing auth headers has a certain level of risk. Reusing Basic Auth headers may work without issue, but reusing SigV4 headers likely won't unless the content AND headers are NOT reformatted
3. If the user provides no auth header option and incoming captured requests have no auth header, then no auth header will be used for outgoing requests

## Diagnostic Dump Modes

In addition to replaying traffic, the replayer binary supports two diagnostic modes that print
summaries of captured traffic without sending anything to a target cluster. These are useful for
inspecting what the capture proxy recorded and diagnosing issues before or during a replay.

Both modes are invoked via the `--mode` flag and reuse the same Kafka/file source configuration
as the replayer (brokers, topic, MSK auth, property file, or `-i` for file input).
No target URI is required.

**Dump modes do not use a consumer group.** They use Kafka's `assign()` API to read
partitions directly, so they will never interfere with a running replayer's committed offsets.
The `--kafka-traffic-group-id` parameter is rejected in dump modes.

#### Offset and time windowing

Dump modes support optional start/end bounds to inspect a slice of the topic:

- `--start-offset N` — begin reading at offset N on every partition (default: beginning)
- `--end-offset N` — stop after passing offset N on every partition
- `--start-time EPOCH_SECONDS` — begin at the earliest record at or after this timestamp
  (uses Kafka's `offsetsForTimes`)
- `--end-time EPOCH_SECONDS` — stop after the first record whose timestamp exceeds this value

Start/end offset and start/end time are mutually exclusive (offsets take precedence if both
are provided). When neither is specified, the dump reads from the beginning of the topic to
the current end.

### Mode: `dump-raw`

Prints one line per TrafficStream record directly from Kafka/file, with no cross-record
aggregation. Each line shows the observations within that single record.

**Usage:**
```
traffic-replayer --mode dump-raw \
  --kafka-traffic-brokers kafka:9092 \
  --kafka-traffic-topic my-topic \
  [--start-time 1709596300] [--end-time 1709596400] \
  [--preview-bytes-read 64] \
  [--preview-bytes-write 64]
```

**Output format:**
```
[1709596356-1709596357] p:0 o:1234 ncs:node1.conn123.5: OPEN W[110]: GET /cat/indic... R[56]: HTTP/1.1 200 OK... EOM CLOSE
```

Fields:
- `[start-end]` — min/max epoch-seconds of observation timestamps within this record
- `p:0 o:1234` — Kafka partition and offset (omitted for file input)
- `ncs:node1.conn123.5` — nodeId.connectionId.streamIndex (matches log format from
  `TrafficChannelKeyFormatter`)
- Observation tokens:
  - `OPEN` — ConnectObservation or BindObservation
  - `CLOSE` — CloseObservation
  - `DISCONNECT` — DisconnectObservation
  - `EOM` — EndOfMessageIndication (marks the boundary between request and response)
  - `DROPPED` — RequestIntentionallyDropped
  - `EXCEPTION` — ConnectionExceptionObservation
  - `R[size]: preview...` — Coalesced consecutive Read + ReadSegment observations. Size is the
    total bytes across the run. Preview shows the first `--preview-bytes-read` bytes (default 64).
  - `W[size]: preview...` — Same for Write + WriteSegment observations, controlled by
    `--preview-bytes-write` (default 64).

Consecutive reads (Read/ReadSegment) are coalesced into a single `R[totalSize]` token.
Consecutive writes (Write/WriteSegment) are coalesced into a single `W[totalSize]` token.
Any non-read/write observation breaks the run.

**Example analysis with unix tools:**

Note: a single TrafficStream record can contain multiple EOM markers (keep-alive connections),
so use `grep -o ... | wc -l` to count all occurrences rather than `grep -c` which counts lines.

```bash
# Count request/response boundaries
grep -o 'EOM' dump.txt | wc -l

# Count opens and closes
grep -o 'OPEN' dump.txt | wc -l
grep -o 'CLOSE' dump.txt | wc -l

# Connections that opened but didn't close within the record
grep 'OPEN' dump.txt | grep -v 'CLOSE' | wc -l

# Records with reads (requests) or writes (responses)
grep -c 'R\[' dump.txt
grep -c 'W\[' dump.txt

# Partial captures — reads with no EOM (request started but didn't finish in this record)
grep 'R\[' dump.txt | grep -v 'EOM' | wc -l
```

### Mode: `dump-http`

Runs the full HTTP transaction accumulator to reconstruct request/response pairs, then prints
one line per request and one line per response as they become available.

**Usage:**
```
traffic-replayer --mode dump-http \
  --kafka-traffic-brokers kafka:9092 \
  --kafka-traffic-topic my-topic \
  [-t 360]
```

The `-t` (packet timeout) flag controls how long the accumulator waits for a connection to
complete before expiring it, same as in replay mode.

**Output format:**
```
[1709596356-1709596357] p:0 o:1234 s:5 nc:node1.conn123: REQ GET /_cat/indices HTTP/1.1
[1709596358-1709596359] p:0 o:1234 s:5 nc:node1.conn123: RSP HTTP/1.1 200 OK
[1709596360-1709596360] p:0 o:1240 s:8 nc:node1.conn456: EXPIRED (ACCUMULATING_READS)
[1709596361-1709596361] p:0 o:1242 s:9 nc:node1.conn789: CLOSED (2 requests completed)
```

Fields:
- `[start-end]` — epoch-seconds of the first and last packet in the request or response
- `p:0 o:1234 s:5` — Kafka partition, offset, and TrafficStream index
- `nc:node1.conn123` — nodeId.connectionId
- `REQ` line — first line of the HTTP request (extracted from accumulated bytes)
- `RSP` line — first line of the HTTP response
- `EXPIRED` — connection timed out before completing; shows the accumulator state at expiry
- `CLOSED` — connection closed normally; shows how many requests were completed

### Implementation notes

- Both modes bypass the replay engine, connection pool, transformers, and backpressure
  (`BlockingTrafficSource`). They use `TrafficCaptureSourceFactory.createUnbufferedTrafficCaptureSource`
  directly.
- For Kafka sources, dump modes create a bare `KafkaConsumer` using `assign()` instead of
  `subscribe()`. This avoids all group coordination and offset tracking. Partitions are
  discovered via `partitionsFor(topic)`, then seeked to the requested start position.
  Offset windowing uses `seek()`; time windowing uses `offsetsForTimes()`.
- `dump-raw` is implemented in `TrafficStreamDumper` — a standalone class with no dependency on
  the accumulator. It iterates observations from each `TrafficStream` record and formats output.
- `dump-http` is implemented in `HttpTransactionDumper` — a custom `AccumulationCallbacks`
  implementation wired into `CapturedTrafficToHttpTransactionAccumulator`. It reuses the existing
  `pullCaptureFromSourceToAccumulator` loop from `TrafficReplayerCore` but with no replay
  machinery.
- The `--mode` parameter is added to `TrafficReplayer.Parameters`. The `targetUriString` positional
  parameter is only required when mode is `replay` (the default). Dump modes branch in
  `TrafficReplayer.main()` after creating the traffic source, skipping all replay setup.
- Output goes to stdout. Log messages go to stderr (via slf4j, same as existing behavior).
