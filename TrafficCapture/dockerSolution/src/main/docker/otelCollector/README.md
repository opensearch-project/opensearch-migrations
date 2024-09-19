## Monitoring Progress via Instrumentation

The replayer and capture proxy (if started with the `--otelCollectorEndpoint` argument) emit metrics through an
otel-collector endpoint, which is deployed within Migrations Assistant tasks as a sidecar container. The
otel-collectors will publish metrics and traces to Amazon CloudWatch and AWS X-Ray.

Some of these metrics will show simple progress, such as bytes or records transmitted.  Other records can show higher
level information, such the number of responses with status codes that match vs those that don't.  To observe those,
search for `statusCodesMatch` in the CloudWatch Console.  That's emitted as an attribute along with the method and
the source/target status code (rounded down to the last hundred; i.e. a status code of 201 has a 200 attribute).

Other metrics will show latencies, the number of requests, unique connections at a time and more.  Low-level and
high-level metrics are being improved and added.  For the latest information, see the
[README.md](../../../../../../coreUtilities/README.md).

Along with metrics, traces are emitted by the replayer and the proxy (when proxy is run with metrics enabled, e.g. by
launching with --otelCollectorEndpoint set to the otel-collector sidecar).  Traces will include very granular data for
each connection, including how long the TCP connections are open, how long the source and target clusters took to send
a response, as well as other internal details that can explain the progress of each request.

Notice that traces for the replayer will show connections and Kafka records open, in some cases, much longer than their
representative HTTP transactions.  This is because records are considered 'active' to the replayer until they are
committed and records are only committed once _all_ previous records have also been committed.  Details such as that
are defensive for when further diagnosis is necessary. 
