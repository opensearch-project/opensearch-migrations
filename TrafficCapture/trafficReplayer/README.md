# HTTP Traffic Replayer

This package consumes streams of IP packets that were previously recorded and replays the requests to another HTTP
server, recording the packet traffic of the new interactions for future analysis (see the Comparator tools).

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

With the exception of the preparation around JSON model and its transformation, all the other handlers (compression,
chunked, and JSON parsing/serialzation), use streaming data models via mostly custom handlers. This should minimize the
memory load (working set size, cache misses, etc). However, attempts have not yet been made to reduce the number of
allocations. Those optimization may not have extremely high value, especially when JSON parsing will need to create
multitudes more objects.

Netty has a vast array of HTTP utilities, but this code base eschews using them in several places because it is simpler
and clearer to work with our HTTP JSON model, which is the interface that request customization will use - for payload
AND header/URI transformations, and to maintain greater control over the exact contents that is eventually sent to the
target URI.

## Transformations

Transformations are performed via a simple class defined by
[IJsonTransformer](../trafficReplayer/src/main/java/org/opensearch/migrations/transform/IJsonTransformer.java). Currently,
this class uses [JOLT](https://github.com/bazaarvoice/jolt) to perform transforms that are composed of modular
operations that are defined in the [resources](../trafficReplayer/src/main/resources/jolt/operations) associated with
the package. Future work will include adding more JSON transformations and other potential JSON transformation tools
(like [JMESPath](https://jmespath.org/)).


## Authorization Header for Replayed Requests

There is a level of precedence that will determine which or if any Auth header should be added to outgoing Replayer requests, which is listed below.
1. If the user provides an explicit auth header option to the Replayer, such as providing a static value auth header or using a user and secret arn pair, this mechanism will be used for the auth header of outgoing requests. The options can be found as Parameters [here](src/main/java/org/opensearch/migrations/replay/TrafficReplayer.java)
2. If the [CDK](../../deployment/cdk/opensearch-service-migration/README.md) is deployed and its Replayer command has not been altered (as in the case of 1.) and CDK deploys a target cluster with a configured FGAC user (see `fineGrainedManagerUserName` and `fineGrainedManagerUserSecretManagerKeyARN` CDK context options [here](../../deployment/cdk/opensearch-service-migration/README.md#configuration-options)) or is running in demo mode (see `enableDemoAdmin` CDK context option), this user and secret arn pair will be provided in the Replay command for the Auth header
3. If the user provides no auth header option and incoming captured requests have an auth header, this auth header will be reused for outgoing requests
4. If the user provides no auth header option and incoming captured requests have no auth header, then no auth header will be used for outgoing requests
