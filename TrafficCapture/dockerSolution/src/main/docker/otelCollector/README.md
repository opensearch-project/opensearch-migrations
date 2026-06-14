## Monitoring Progress via Instrumentation

The replayer and capture proxy emit telemetry through OTLP collector endpoints when started with
`--otelMetricsCollectorEndpoint` and/or `--otelTraceCollectorEndpoint`. The otel-collector endpoint is deployed within
Migrations Assistant tasks as a sidecar container. The otel-collectors can publish metrics to Amazon CloudWatch and
traces to AWS X-Ray.

For CDK/ECS deployments, the default sidecar config is `/etc/otel-config-aws-metrics.yaml`, which exports metrics to
CloudWatch only. Enable trace collection explicitly to use `/etc/otel-config-aws.yaml`, which adds the AWS X-Ray trace
pipeline.

Some of these metrics will show simple progress, such as bytes or records transmitted.  Other records can show higher
level information, such the number of responses with status codes that match vs those that don't.  To observe those,
search for `statusCodesMatch` in the CloudWatch Console.  That's emitted as an attribute along with the method and
the source/target status code (rounded down to the last hundred; i.e. a status code of 201 has a 200 attribute).

Other metrics will show latencies, the number of requests, unique connections at a time and more.  Low-level and
high-level metrics are being improved and added.  For the latest information, see the
[README.md](../../../../../../coreUtilities/README.md).

Along with metrics, traces are emitted by the replayer and the proxy when the trace endpoint is configured, e.g. by
launching with `--otelTraceCollectorEndpoint` set to the otel-collector sidecar.  Traces will include very granular data for
each connection, including how long the TCP connections are open, how long the source and target clusters took to send
a response, as well as other internal details that can explain the progress of each request.

Notice that traces for the replayer will show connections and Kafka records open, in some cases, much longer than their
representative HTTP transactions.  This is because records are considered 'active' to the replayer until they are
committed and records are only committed once _all_ previous records have also been committed.  Details such as that
are defensive for when further diagnosis is necessary. 
