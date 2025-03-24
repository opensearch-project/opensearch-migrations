# Scaling Traffic Capture and Replay

## Background

### What is Scale?

Scaling can mean a lot of things but a north-star can be to replicate the biggest traffic loads that Elasticsearch and OpenSearch clusters deal with today.  Clusters may have hundreds of nodes transmitting traffic at > 20Gbps.  Functionally, we’ll need to support those clusters by

1. Transcribing traffic for later replay
2. Storing and organizing it so that it can be replayed
3. Transforming traffic into requests and further transforming those requests
4. Replaying the requests in a timely manner
5. Associating the source transactions with the target transactions and recording them for future analysis

Non-functional requirements to scaling will include

1. Surfacing aggregate metrics to let customers understand the progress and quality of the target’s performance.
2. Providing tooling to diagnose issues with the capture or replay, including for specific connections and requests.
3. Minimizing costs, which will considerable and will be a showstopper for some.
    1. Scaling resources to provide adequate performance and reasonable costs.
    2. Functional optimizations to make a replication cheaper and quicker.  (e.g. [compressing quiescent periods](https://opensearch.atlassian.net/browse/MIGRATIONS-1525))

### What Replication is and What It Isn’t

Replaying requests to recreate an exact sequence of side effects on a target is straightforward in an environment devoid of concurrency. noSQL services like Elasticsearch are not devoid of concurrency and are anything but straightforward to reason with.  Without replaying traffic, it can be impossible to precisely predict the order that requests will be processed for a distributed service like Elasticsearch.  The problem isn’t even one just of ordering requests, because of internal states updating at various times across nodes within a cluster.

For example, consider a bulk request that puts different documents to different indices.  Assume that the indices live on different nodes.  Also assume that around the same time another query may be combining results from both of the impacted indices.  Elasticsearch clusters are composed not of multiple layers of nodes and data is organized into shards that maintain their own causality structures.  The results of the query request will be dependent upon the order that changes are committed to the nodes that are being used to service that query.  Another identical query could be sent at the same exact time and exhibit different results.

This general problem is worse than a typical ‘eventual consistency’ problem.  We’re not worried *just* about eventually getting consistent data between nodes.  If that were the only concern, we’d need to be looking into how to replicate data via each shards’ translogs (what keeps the order of commits to make for each replica, keeping the replicated order and state completely defined).  We’re trying to show the user that the target cluster will behave ‘as-expected’ to the source cluster.

“As-expected” is an even less-defined problem than trying to determine causalities across racing parallel processes.  Fortunately though, it’s the problem that 1) the customer needs to determine before completing any migration (think generally of UAT before design, code, scaling changes) and 2) we don’t need to make it for them.  We’re helping the customer make that judgment by simulating their source environment with completely representative client traffic so that customers can determine when data model inconsistencies are present, performance degradation is too severe, or the quality of query results is too different.

## Problem Statement

### What a Replay Needs to Do

As proven above, there are going to be differences between the source and cluster.  In fact, finding differences isn’t a defect but one of the main features of the traffic-replay strategy, along with replication.  Users will make their determinations based upon how sensitive their applications are to the changes and how much retooling they may need to do to accept them.  Therefore, it’s important for these tools to minimize differences as much as possible.

For our first cut, closely matching the pacing and sequencing source request stream should give the customer a very good indication of how the cluster being replicated would behave if it had replaced the source cluster.  To do that, let’s work out the following requirements on requests.

* So that we can provide the same level of stress to the target cluster that the source was experiencing, the relative timing between requests should be preserved.
* When we **know** that some requests were handled *after* previous requests, that ordering should be preserved to the target cluster.  The obvious case where we can make this determination is when requests were on a common socket.

Notice that the ordering requirement will contradict the first two requirements that are concerned with time.  If a request can only be replayed after a previous request has been handled, and the previous request is being handled by a system outside our control, there will be cases where slow responses from the target cluster are greater than the gaps in the original source request stream.  We can either weaken the first or second requirement.  Weakening the second by running more requests in parallel/pipelines could be much harder to reason with.  That would introduce a new variable and causality could occur very differently, creating a large number of differences that would need to be evaluated downstream.  To resolve this contradiction, we can use a new requirement that when the exact pacing cannot be maintained, we can at least keep the requests approximately in-sync.  Since updates between individual nodes within a cluster may take seconds, or much longer when a cluster is under stress, that provides justification for affording requests to have a replay jitter within seconds.

### Requirements

In priority order then, our requirements are to

1. Maintain the ordering of requests that were within the same source connection (aka: Channel/Socket).
2. Uniformly slow down traffic across all traffic so that the relative times that requests are sent is within some number of seconds of the original pace.
3. When all connections can keep up with the specified pace (which will be some multiple of the original), all requests will be sent at that pace within some discrete tolerance factor.

## What We Already Have

A solution whose simplest unit of work is a decrypted captured **connection**.  Each connection (channel) comes from only one client and goes to only one server.  We only support HTTP/1.1 today so each connection is already throttled naturally by the responsiveness of the client and server.  HTTP2 is a major concern for OS2→OS3 migrations, but that’s outside the scope of this document.  For the capture proxy, we spin up many **event loops** (threads that have their own independent event-pumps) to handle incoming connections and likewise spin up separate event loops for outgoing connections.  The connection to event loop assignment is sticky for the process.  For the proxy, connections are assigned by netty when the client makes an incoming connection.  For the replayer, assignments are made BEFORE the connection is made so that transformation work can happen on its own thread.

A **proxy** that captures the TLS-terminated traffic exactly as it goes into the cluster.  It can preserve the original timestamps and are able to maintain the ordering of packets already within a connection (see [OrderedStreamLifecyleManager](https://github.com/opensearch-project/opensearch-migrations/blob/main/TrafficCapture/captureOffloader/src/main/java/org/opensearch/migrations/trafficcapture/OrderedStreamLifecyleManager.java) and how it is used).  [StreamChannelConnectionCaptureSerializer](https://github.com/opensearch-project/opensearch-migrations/blob/main/TrafficCapture/captureOffloader/src/main/java/org/opensearch/migrations/trafficcapture/StreamChannelConnectionCaptureSerializer.java) and the [LoggingHttpHandler](https://github.com/opensearch-project/opensearch-migrations/blob/79faea3d8197aea9e4fe64676f41fdc7d4fab8e5/TrafficCapture/nettyWireLogging/src/main/java/org/opensearch/migrations/trafficcapture/netty/LoggingHttpHandler.java#L31) can chunk arbitrarily long connections into separate records.  Notice that the Proxy buffers captured and formatted traffic in memory while it waits for them to be offloaded.

A **Kafka topic** that receives traffic from the proxy as a stream of records and distributes them to a group of replayers.  Kafka can scale topics horizontally by dividing a topic into partitions.  Kafka manages consumer scaling by assigning partitions of a topic to clients within a client group and only one client in a group will be assigned a partition at a time.  Messages from assigned partitions are delivered to the consumer in the order that they were committed to the partition.  As clients come and go, so do partition assignments, which are managed by the broker.  One caveat on partitioning is that once the number of partitions is set, the partition assignments for recorded items will also be set.  If the partitioning scheme changes, data with the same partition key could be assigned to a partition different from its previous assignment.  Since connectionIds are the partition key and connections can be very long-lived, changing the scale of the Kafka topic breaks an otherwise naive parallelization strategy as connections may now straddle partitions and therefore consumer processes.

A **replayer** that pulls messages from Kafka and commits the requisite ones *after they’ve been fully processed*.  Splitting the commit from reading records allows us to guarantee at-least-once delivery of every request.  The client can withstand being severed from the Kafka group and reconnecting.  Some care is taken to *not* commit watermarks for processed messages if those messages were from a partition that had been unassigned (notice that the latest changes to fix collisions in duplicate target connections for expired capture accumulations probably is vulnerable to this).  While the replayer guarantees that every transaction will be processed at-least-once, it may run some requests out of order.  If a replayer is unassigned a partition, there’s an inevitable race condition to stop sending data that’s running or has been scheduled in different threads.  Kafka won’t reassign a partition immediately (the reassign time will be dependent upon the broker/group configuration).  However, since the replayer produces on one thread and blindly consumes on another, we will have cases when requests are replayed from a zombie-instance.  when earlier requests from a topic-partition are run due to error recovery.

At the highest level, the replayer pulls TrafficObservations from a source of TrafficStreams, reconstructs those into requests, and makes them to the target server.  Pulling the traffic is done via a **single-threaded producer** that loops over calls to Kafka.  When data was pulled from Kafka, that data is sent to **a single threaded state machine** that groups observations from different connections into separate streams.  When each the state machines finish parsing a transaction, a callback is called to **enqueue the transaction** for transformation and emission.  That enqueueing step is non-blocking.  Items will persist in the queue until a consumer thread pulls them out and assigns them to their final **netty event loop** and **schedules** their transformation/emission.  There are multiple points that impact the amount of memory that is consumed by this multithreaded producer-consumer model which are detailed below.

The proxy and replayer publish metrics and traces through **OpenTelemetry**.  The applications are configured to send that instrumentation to an OTLP collector.  Currently, we deploy a single collector ([AWS Distro for OpenTelemetry](https://aws-otel.github.io/)) in our ECS cluster and setup a DNS entry with service discovery.  The collector itself is configured to send data to CloudWatch and X-Ray for AWS deployments and a container-managed Prometheus and Jaeger services for the vended docker-compose solution.  None of the configurations in the otel-collector prevent it from being scaled horizontally as the sink systems receiving instrumentations are able to handle instruments from a large number of clients.

We don’t have much for **verification**.  Today, we’re writing some metrics to show similarities in performance.  Scaling those metrics will work as well as we can scale the replayer and the metrics storage systems.  We also write the outputs to an EFS volume from the replayer.  The files output are much more verbose than what they need to be (there are data redundancies, text/json data is stored as BASE64 encoded contents within other json, etc).  All files are written via log4j2 to a shared directory.  The limits of that EFS volume are unknown.  To date, we have only spun up a handful of replayers at a time to support A/B testing, with those results being written to different sibling directories.

## Supporting Scaling Generally

### Otel

I don’t think that there’s a library to go straight to CloudWatch and X-Ray.  If there were, that might be preferable.  Otherwise, running the collector as a sidecar for the replayer with a local bound address for the replayer’s collector is the next best option.  Since the proxy is run by the customer, how the otel collector should be deployed may be more contentious.  We should be able to use the same image/container configuration as for the replayer.  The image as it is today is 100MB

### Tuning & Load/Stress Testing

The first question we’ll get is, how do I set it up.  If a single container and Kafka broker can’t cut it, then how many will we need?  How much memory, how much disk, etc?  With practice we’ll get better at knowing what those numbers are if we’re able to properly observe the underlying systems.

How does the system fail?  How will we recover?  When should we not recover?  What will the user see?

### Unit Testing

At scale, 1/million and 1/billion problems happen a LOT more often.  We need to simplify interfaces and test them much more carefully.  There are a lot of high-level functional tests, but we need to go down a level and write more granular targeted functional tests.  The designs are maturing enough that the interfaces will be solidifying, making the investment have more value than it would have had 6 months ago.

## Scaling Kafka

We need a “get out of jail free” card to reconfigure which topic(s) we’re publishing and consuming because repartitioning Kafka while there are active connections will cause some connections to be split across multiple partitions.  We have no way of preventing that if there are active connections at the time of a repartitioning.

Since connections are handled independently of each other, other than to synchronize time, we don’t need to care where each connection’s stream came from.  If connections were fanned across multiple partitions or multiple topics, the bank of replayers would still be consuming separately partitioned and independent streams of data.  Making the topic dynamic for the producer and consumer clients doesn’t break the independent-other-than-time model for connections as long as we can keep stickiness on the brokers used for a given connection.  That implies that there will be long drainout periods where an old cluster is (or was) servicing long-lived connections.

## Scaling Replays (in progress)

We have the multithreaded producer-consumer model due to constraints on the Kafka consumer.  We have to keep the thread that interacts with Kafka non-blocking so that the poll() call occurs frequently enough to keep the consumer in the group.  If the consumer falls out of the group, commits can’t be performed,

Backpressure is necessary so that the replayer doesn’t overwhelm its memory footprint with too much work in the future.  However, we need some buffering so that we have requests ready to send as per their schedule.

### Backpressure

For the replayer, consuming from multiple partitions may have a very acute impact on memory utilization and/or correctness.  The replayer throttles consuming more messages by time.  It puts the Kafka consumer into a “paused” state, which is a Kafka concept to keep the consumer within the group/assigned to the partition but not receive new records until resume is called.  Unfortunately, in the current model, records from all partitions are lumped together to into a single stream of TrafficStreams by the (Blocking)TrafficSource.  The ReplayEngine applies backpressure on the BlockingTrafficSource by setting a time-horizon to consume messages only up to that point.  That works well for a single partition where we’ll only pull one record beyond the timestamp, then wait until the ReplayEngine’s scheduled an ongoing work commences to push the time horizon, which will eventually restart consumption from Kafka.


## Scaling Capture

Nothing needs to be done to scale it, other than general optimizations.  Instead, it will need to undergo changes to facilitate scaling for other systems, like Kafka, and possibly instrumentation (but probably not, as we’ll go the sidecar route and just use a different argument at the command line).

This is very good since we deploy the capture proxy in the customer’s infrastructure.

## Changes required

### Non-Functional:

**High Priority**: Share how to operate the solution at scale

* (X-Small LOE) Setup a runbook file in our github repo.

**High Priority**: E2E Load Tests - knowing if it will work for a customer

* (Medium LOE ) setup an AWS test and confirm the results for millions of requests and millions of documents.
    * Configure  A) 3 ES+proxy nodes; B) 2-3 Kafka brokers with 4-6 partitions for topics; and C) 3 replayers.
    * Begin the test with the replayer disabled for a period of time for historical backfill to complete.
* (Large LOE) Confirm the responses that came back from the target were expected.  This will involve beginning to solve the difficult parts of validation.  How do we extract what’s required and what do we need to ignore.
* (Medium LOE) Validate that all the original requests were captured correctly by matching test-client request activity logs with metrics and outputs.
* (Medium LOE) Publish results so that we can look back at every load test ever done - being able to restage each test at any point in the future.
    * Make sure that datasets are stable and durable.
    * For each component, capture CPU, memory utilization, network load for the duration of the run to graph later (they’ll otherwise expire)
    * Reference the logs for each of those components so that they’re available for at least several weeks

**Medium Priority**: Can we dynamically increase/decrease Kafka’s horizontal scaling

* (Small LOE, dependent upon general load tests) Run the tests above while scaling Kafka up and then down.  Make sure that some rescaling activities are happening while the proxy and replayer are both running.  Observe that no requests are dropped and all are delivered as expected.  Relative times between replayed transactions may be impacted but there should be no lags for TrafficStream timestamps.

### **Otel:**

**High Priority:** We can’t scale at all without this.

* (Small LOE) Configure/Run the otel-collector as a sidecar for AWS deployments.  We’ll want to pare down the logs and make sure that they clearly show which application each instance pairs to.

**Medium Priority:** We don’t know how it fails, recovers and how our systems behave in those cases.

* (Medium LOE) Stress the collector to the point of failure.  Write tests to bring up a collector container and configure the endpoints to go through a toxiproxy instance.  Configure a suite of tests to show:
    * Varying loads & steady loads of traces.  Notice that metrics can’t be changed much.
    * Independently set different stresses on the toxiproxy for metric & trace publishing.
    * Publish test results and link them from a run book.

**Low Priority**: (maybe higher for DMS) Process logs through otel-collector for non-console logs?

* (Medium LOE) Publish additional logs (tuples?, progress, etc) through the collector to CloudWatch.
* (Large LOE) Publish some logs to a searchable datastore like OpenSearch.
* (Large LOE) Transform results into structured data and publish them to a structured datastore like DDB

### **Proxy**:

**High Priority**: We need to confirm that it’s working as expected

* (Small LOE) write unit tests to show metrics and traces are emitted as expected.  The requestGathering traces are not always present today.
* (Small LOE) write integ tests

**Medium Priority:** We can’t re-scale Kafka without restarting.  We need to know the scale BEFORE deploying, but we can figure that out for first migrations.

* (Small LOE) Implement a control-plane topic to configure which cluster/topic that new connections should be written to.  This will need to be configured upon startup to point to the initial payload topic.  We’ll also need a utility to run from the migration console to spin-up a new Kafka cluster with appropriate settings.  That utility will then publish a record to the control stream with the timestamp and the new URI of the new topic.  The retention on the control plane stream should be for (or longer than) the lifetime of the data in any of the clusters.
* (Small LOE) For the proxy, store the Kafka producer that was used to publish the first record so that all subsequent records will be sent through that producer.  New connections will pull the value from the control plane’s configuration.
* (Medium LOE) The proxy will not publish when it has flushed all all connection data to deprecated cluster/topics.  It will take a command line argument to selectively enforce a time-limit on how old any connection can be.  When a topic switch is sent, the proxy will terminate any connections still outstanding with the old kafka producer as per the configuration.  That will put a predetermined upper-bound on how long a replayer will need to continue to read from old topics. This could be an hour or a day as we don’t anticipate to have too many rescaling events.  Much of the effort here will be for test construction.

### Replayer:

**High Priority**: Publish, test, and peer review the contracts for each module.

* (Large LOE) Refactor the code base and document each component’s responsibilities.  It sounds unrelated to scale, but setting up tighter modules with narrower responsibilities will make it easier to reason and test each of those modules and contracts.

**High Priority**: Partition based read-limiting

* (Medium LOE) Keep track of the time watermark for each individual partition.  When time is advanced, only resume those partitions whose last watermarks are now in the past.

**High Priority**: Coordinated time (not [subsidized time](https://infinitejest.wallacewiki.com/david-foster-wallace/index.php?title=Subsidized_Time))

* (Medium LOE) Implement clock skew for all scheduled tasks.
    * When a scheduled task is run, check that the progressed time is not too far behind the expected time for this point.  If it is, reschedule.  Keep track of this value in an Atomic for all tasks.
    * Alternate - can we do an iOS like trick and change the definition of time that netty is using?  (I can’t find a way)
    * Alternate 2 - schedule at least one task per event loop but do not schedule any past the nearest one and instead drop them into a PriorityQueue.  When each timer comes up, run the earliest one(s) if we don’t need to wait.  After the tasks that need to run now are run, schedule for targeted time based on the current top of the scheduled task PQ.  We may want to keep times in an even time cadence and only shift the interpreted time (it may make logging simpler)
* (Medium LOE) Implement clock synchronization.  Use Kafka to publish the current “progressed time” that each replayer is at.  How do I know when all clients in the group have progressed?  Could this be by partition?

**Medium Priority**: Kafka hot-swapping/scaling support

* (Large LOE) Read from the ‘control plane’ topic to resolve which topics may have active records.  For a small number of topics, we can read from all the topics with different TrafficSources in parallel.  As time goes on and our time cursors advance, we can drop away old traffic sources.  It would be simplest to have one BlockingTrafficSource manage a number of separate Kafka sources and let the rest of the replayer pushback on just a single interface rather than need to divide the replayer N-times.

**Medium Priority**: Reduce the memory footprint unknowns for a user

* (Medium LOE) We treat all connections/requests the same from a throttling perspective when applying backpressure.  We should take into account the incoming size of the requests coming in and the size of the transformed objects going out.
* (Small LOE) Adapt the amount of time that is necessary for transformation.  I think that we allow 1s for transformations to happen.  If a transformation can run in 10s, we’re buffering 99x longer than we need to.  Having some buffer is good since things are asynchronous, but 2-5x should be enough.

**Medium Priority**: Show how configurations will impact the amount of memory required.

* (Medium LOE) Instrument the code and confirm that existing metrics show approximately how much memory is being consumed at each stage of a connection/requests processing pipeline.

