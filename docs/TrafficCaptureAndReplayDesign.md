# Traffic Capture and Replay

## Overview

Two main components support cluster mirroring. The first component is the Capture Proxy, which relays network traffic
from HTTP requests to a source cluster into a durable, scalable stream. The second component, the Traffic Replayer,
replicates the traffic observed by the Proxy onto a target cluster. In this case mirroring does three things.

1. Illustrates differences in behavior between the source and target clusters.
2. Stresses the target cluster very similarly to the source cluster.
3. It keeps a target’s documents and metadata in sync with a source cluster.

Data is buffered through Kafka from the proxy to the replayer. The components also send metrics and traces to an
otel-collector, which is deployed as a sidecar, which in turn publishes instrumentation.

Here are the main steps to synchronize a target cluster from a source cluster:

1. Traffic is directed to the existing cluster, reaching each coordinator node.
2. A Capture Proxy is added in front of the coordinator nodes in the cluster, allowing for traffic capture and storage.
   (see [here](./ClientTrafficSwinging.md) for details about how to use an ALB to do this).
3. A historical backfill is triggered to synchronize the documents in the target from the source as it was at some
   point in time. That point in time will/must be after all traffic has been captured.
4. Following the backfill, the Traffic Replayer begins replaying the captured traffic to the target cluster.
5. The user evaluates the differences between source and target responses.
6. After confirming that the new cluster's functionality meets expectations, the target server is ready to become the
   new cluster. Note that the target continues to be synchronized from the replayer. If customers are especially
   concerned about greater durations where results may be inconsistent due to lag between the source cluster and the
   target cluster (ideally this is around 1s), the capture proxy could reject modifications, forcing clients to
   resend shortly, allowing the target cluster to pickup those modifications as the target fleet replaces the source
   fleet.

## Capture Proxy

The Capture Proxy terminates TLS and replicates the decrypted read/writes streams as they arrive to Kafka. Since the
Capture Proxy is handling data on the critical path for the source cluster, the proxy is designed to offload data as
efficiently as possible to minimize the proxy’s impact on overall performance (latency, load, etc). The Capture Proxy
parses its TLS configuration the same way as OpenSearch, from a yaml config, with the same keys.

The proxy is expected to supplant the original source cluster endpoint so that clients can continue to operate without
any changes. One way to accomplish that is to install a proxy alongside the source cluster’s coordinating nodes and
shift the coordinating nodes’ configuration to use a port bound only to the loopback address and likely without TLS,
as encrypting local traffic with TLS is expensive and unnecessary. Another approach is
described [here](./ClientTrafficSwinging.md).

The proxy can also be deployed on standalone hardware. However, two caveats remain.

* The proxy is only designed to proxy traffic for a single destination. If that destination is a large number of nodes
  with a load balancer, any number of proxies that are necessary to support the traffic load can be setup and all will
  send traffic though the nodes that the load balancer is using.
* The second caveat to installing the proxy on separate hardware is that the infrastructure will need to change and
  traffic will need to be routed exclusively through the proxy, which itself is more infrastructure to change. This will
  also increase latency for all traffic over a colocated solution.

### TLS

In order for the proxy to write data that can be replayed and used for comparison, the request and response data must be
stored so that HTTP messages can be reconstructed at a later point in time. If an existing client and server (cluster)
are using TLS to transfer data, that data will first be decrypted before being offloaded. When using Amazon Managed
Streaming for Apache Kafka, AWS Authentication is used, data is sent via TLS, and it is stored in an encrypted format.

### Additional Impact

In addition to the impact incurred from TLS decrypting and encrypting, there may be a significant impact to network
load. This solution assumes that the network has enough capacity to double the network traffic, albeit to different
destinations. The proxy doesn’t compress traffic because many requests and responses may already be compressed.

If a PUT or any other mutating call is dropped from the replay, it could have a long-lasting and irreversible impact on
all future results. Because of that, the Capture Proxy parses the incoming stream as HTTP messages to determine the
importance of an HTTP request. All GET traffic is immediately forwarded to the source cluster while data is
asynchronously offloaded to Kafka. Mutating requests such as PUT, POST, DELETE, PATCH etc are handled more carefully.
The Capture Proxy makes certain that all mutating requests have been committed to Kafka before the request is fully
‘released’ and sent to the source cluster. This behavior means that GET traffic should flow through the system without
being impacted by the latency of calls to Kafka. However, mutating requests will be impacted. Clients that have made
those requests will not receive a response or will not be able to make another request until all prior offloaded traffic
for the connection has been committed (which could include offloading previous GET requests that had been sent on the
same connection).

That guarantees that no mutating request was sent to the source without first being committed to Kafka. However, it also
means that a request could be committed to Kafka without ever being sent and handled by the downstream service. Requests
that are suspected of not being processed (or fully processed) by the source cluster are detectable by the Capture
Proxy. Those requests will be missing a response. Notice that since responses themselves may not be fully returned in
every case that its request is handled, there may be other cases where a mutating request DID succeed on the source
cluster but no response is present. The Capture Proxy doesn’t yet reconcile the which of these requests have likely
succeeded and which have failed. However, in practice, many real-world examples would have retried the failed request,
resulting in a received response.

All network activity is asynchronously data-driven, using the same framework (netty) that Elasticsearch, OpenSearch, and
many other projects use. Using that same framework also mitigates some risk that HTTP could be parsed differently by the
source and the proxy.

### Protocol

Captured data is organized into TrafficObservations (Read, Write, Close, etc) that have timestamps and are organized
into larger “TrafficStream” objects which are written as records to Kafka. These observations are serialized
as [Protobuf](../TrafficCapture/captureProtobufs/src/main/proto/TrafficCaptureStream.proto) wrappers to the raw bytes
that were received or sent by the Proxy sans TLS. TrafficStream objects are organized by connection, with each socket
connection represented by a sequences of TrafficStreams, which will have TrafficObservations for that connection only.
Those TrafficStreams are flushed to Kafka after buffering or after a mutating request has been received. Concurrent
connections will have TrafficStream objects that are interleaved with each other. Each TrafficStream will have its own
respective connectionId, which is a globally unique id for that connection. A unique nodeId is also included for
diagnostics and future Kafka partitioning.

## Traffic Replayer

The Traffic Replayer parses the Protobuf encoded objects recorded by the proxy; reconstructs them into HTTP requests;
sends them to the target cluster; and records the responses alongside the request and the traffic from the original
source interaction.

The Traffic Replayer must group TrafficStreams by their connectionIds into reconstituted TCP streams. Individual
requests are parsed and sent through a processing pipeline that rewrites the request as necessary, then schedules and
sends the requests to match the source time intervals. Responses are aggregated along with the request and source
messages. Following that, the TrafficStream objects are committed from the Kafka topic so that they are not processed
again by a future or concurrently running Replayer.

### Message Transformation

The reassembly process of the captured traffic is careful to preserve timestamps of the originally observed traffic.
Once all the bytes for a request have been accumulated, the bytes are sent through a netty pipeline for transformation.
As per configuration, this processing may include rewriting headers, such as the Host value, changing
User-Authentication, and transforming the contents of the payload. The TrafficReplayer parses the original source
request traffic stream into a Map object with the headers and payload of the original message in a json-friendly
key-value structure. That Map object is passed through an IJsonTransformer object that may rewrite the request by
altering headers or the body. To minimize unnecessary and expensive operations, the netty pipeline parses the HTTP
headers first and runs the transformation before the pipeline has been fully configured. If based upon the headers only,
the transformation did not attempt to access the payload, the pipeline won’t be configured to parse the json from the
body from the message. The pipeline will attempt to setup as few handlers as possible to eliminate unnecessary (de)
compression and repackaging.

When the initial transformation has attempted to access the payload, the entire message needs to be transformed. In that
case, netty handlers are attached to do all of the work required to parse an HTTP payload into a json-like Map (HTTP
chunking, decompression, json parsing, followed by compression, etc). Generally, the handlers stream data as much as
possible so that efficiency can be maximized.

At the end of the transformation pipeline, a new sequence of network buffers has been formatted and is ready to be sent
to the target server. The shape and pacing of the sequence of buffers should closely match that of the original
sequence. In other words, if the source got 120 bytes with one byte per second, the target request will also get 120
parts over 120 seconds.

In some cases, the pipeline may be unable to parse a message, or the message might not require any rewrite. In those
cases, the parsing of the current request is unwound and the request is sent exactly as sent to the source to the target
cluster. The response will be handled like any response for a fully transformed message, though the final metadata will
show whether the request had transformation skipped or if it was due to an error.

This message transformation also includes rewriting authorization headers. In the Basic-Auth case, that rewrite will
only involve the headers. If there were no other transformations, the body of the content will not need to be parsed.
However, if the authorization scheme being used is AWS Auth (SigV4), a handler to parse the body will be added to the
pipeline alongside mechanisms to fully consume the contents so that the signature can be accurately computed.

#### Auth Caveat

The Replayer doesn’t have a way to determine the validity of the incoming messages. It doesn’t have the HTTP basic-auth
passwords, nor does it have access to the public keys used by SigV4. This creates a significant security issue that
currently diminishes the value of auth for OpenSearch clusters. Currently, all requests, regardless of whether they were
validly or maliciously signed will be rewritten with the same auth headers as per the configuration. We can’t leverage
the responses to determine validity since there will be some changes that the replayer must run even though there was no
response present.

#### User Transformations

Users may specify what Transformation to run by providing a jar file that can load an implemention of the
IJsonTransformer class via Java’s ServiceLoader. As described in Message Transformation section, the complexities of
parsing HTTP messages are abstracted away. A transformer can switch off of URI paths, headers, and run sophisticated
json remapping by pulling in libraries such as Jackson, GSON, or json manipulation packages like Jolt or JMESPath. As
progress continues, additional transformations will be developed to accomplish the required transformations between
versions, plugins, etc more easily.

### Timing

The Replayer may be started long after the Capture Proxy has begun recording traffic. Recall that the Replayer tries to
match the client request traffic exactly as it was received by the source cluster so that when comparing results, both
clusters, for any request, the clusters would have been undergoing the same stresses. To do that, the Traffic Replayer
manages its own sense of time to send requests to the target. It shifts the original requests’ timestamps uniformly so
that delays between each observation and request can also be preserved.

A replay will generally start at the beginning of the captured stream and it will fix the current time to the time of
the first interaction. For users that would like to catch-up or stress test the system, the Replayer’s time mapping
function can include a speedup factor (F) so that something that happened N seconds after the initially observation will
be scheduled N/F seconds after the Replayer has started. This functionality is managed by a TimeShifter class that is
effectively just a function that maps scalar values after some initialization. Timing values can be controlled via
command line parameters.

That timing drives much of the rest of the TrafficReplayer. When a request is fully reconstructed, the message
transformation work is *scheduled* to be done just before it would be scheduled to be sent. That’s to guarantee that
temporally sensitive values like SigV4 signatures won’t go stale. It also keeps more data within the same thread, making
for less contention (cache invalidations) and allows for simpler code.

### Sending Requests

Like the Capture Proxy and the transformation pipelines described above, requests are sent via netty. Netty’s
architecture allows for a large number of requests to be handled concurrently through cooperative multitasking. While
that requires code to be data-driven and to never block, it also affords the use of simple data structures that need not
be thread-safe across multiple threads. Several classes are designed to only run from a single thread. Those will be
initialized for each worker thread that netty spins up (which can be specified on the command line).

Netty manages connections to the target cluster within its own EventLoops. An EventLoop within the TrafficReplayer will
have a connection to the target service, which may break and need to be reestablished. However, that same EventLoop (and
its Thread) will be affiliated with its connection for the lifetime of the connection. That lifetime will be terminated
either when the connection’s TrafficStream has encountered a “Close” observation OR if no observations have been
encountered in a period of time and they are expired by the accumulation phase.

Each connection’s EventLoop and Channel are grouped within a ConnectionReplaySession. This session object also includes
data to schedule the interactions for transforming requests, sending them, and closing connections. The schedules are
maintained as a key-value map from the time that an operation should happen, post time shifting (so in real-time, not
source time). As work items are completed for a connection’s session, the next item is pulled from the schedule if it is
ready to run or a timer is set on the EventLoop to rerun it when appropriate. Because there are multiple interactions
scheduled within each connection’s session and each is run sequentially and exclusively, the actual times that
interactions occur could drift. For example, assume the source service took 5 seconds to service each of 6 requests
sequentially on one socket connection that was kept alive. If the target service takes 10 seconds to service each
request, the target will take 60 seconds to run those 6 interactions instead of 30 from the source cluster.

However, if a source cluster had the same interactions and latencies for 6 requests BUT sent them on separate
connections, the total time to send all the requests to the target service could be 35 seconds, since requests would
overlap by 5 seconds. Currently, schedules are isolated to a given connection. Since a connection is defined by a
sequence of requests, we must wait for the previous requests to finish before proceeding. Without the connection
isolation though, requests will be sent without those constraints.
***This does create a caveat that if the target cluster cannot keep up with the pacing of the source cluster, the
Replayer may not be able to match the timing patterns that the source cluster experienced.***

### Throttling

The Traffic Replayer has a TrafficCaptureSource that it uses as an abstraction over a Kafka topic. If the Kafka topic
has days of data, it’s critical that the Replayer consume and hold only the data that is necessary to effectively send
the requests as per the above constraints. The Replayer needs to throttle its Kafka consumption so that it can keep its
memory footprint manageable. To figure out what’s necessary, the Replayer needs to consume enough data so that it can
reconstruct the TrafficStreams into requests.

Within a user-specified window, we expect to either accumulate more observations for a TrafficStream or expire it and
give up. That prevents leaking memory within the Replayer when Proxy’s had faults that prevented them from sending close
events for streams. That limit is independent of how far ahead in the recorded stream should the Replayer advance to
while consuming input. Setting that backpressure ‘lookahead’ limit to a multiple of the expiration window provides a
backpressure to limit memory. However, those two limits still don'’t put a total bound on the peak amount of memory
required for the Replayer. Such a bound would be problematic as there could be an unlimited number of simultaneous
connections with ongoing traffic. If a bound were enforced, progress may not be made on accumulating all of the
requests, trading an Out-of-Memory situation with a deadlocked replayer. The right solution in those cases is to scale
the Replayer to use more memory, either vertically or horizontally. Right-sizing in these cases will be an exercise in
understanding peak load to the source cluster and/or trial and error for provisioning the Traffic Replayer.

The TrafficReplayer may try to send too many requests simultaneously. This will happen if there aren’t enough ports
available on fleet of replayers to support the number of simultaneous connections in use. This can occur when the number
of Replayers is much smaller than the proxy fleet or if the time speedup factor was high on a modestly sized cluster. In
these cases, an additional throttling mechanism is required to restrict how many simultaneous connections can be made. A
command line option is available to limit how many requests can be in-progress at any point in time. When a request has
been reconstructed, if the Replayer has already saturated the total number of requests that it can handle, the new
request will be blocked from sending until other requests have finished. Notice that this is currently bound to the
number of requests, not connections, though a connection will only handle only one request at a time, so this is a rough
substitute for the number of connections.

For customers that need to achieve higher throughputs, they can scale the solution horizontally.

### Horizontal Scaling

The Capture Proxy writes observations to a Kafka topic. Given that Kafka can accept writes from many clients at high
throughputs, the Proxy can easily scale to any number of machines. Like the Proxy, the Replayer simply consumes from
Kafka and Kafka can support many consumers. Since connections are the lowest level atomic groupings and a single client
and server at a prior point in time were already handling that connection, by scaling the Kafka topic for the
appropriate number of Proxies and Replayers, we can handle arbitrarily large loads.

However, the scaling factor for Kafka is the number of partitions. Those partitions will need to be setup to key off of
the nodeId or the connectionId. The partition count itself will need to be set at the time that a topic is created so
that traffic for a given connection never gets remapped while a connection’s observations are being written to the
topic.

### Outputting Results

The Traffic Replayer uses log4j2 for its application logs. It also uses Log4J2 to output some of its other output,
including logs destined for metrics and the results of the source and target traffic interactions. Care should be taken
in adjusting the log4j2.properties files, when necessary.

The results, which are logged not just through log4j2 but also to a file, which is provided by a command line parameter.
This result output will be a stream of json formatted objects with the source/target requests/responses. Those will
include headers, timestamps, and the full bodies base64 encoded.
