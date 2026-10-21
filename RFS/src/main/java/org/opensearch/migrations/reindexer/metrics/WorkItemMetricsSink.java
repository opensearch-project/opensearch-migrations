package org.opensearch.migrations.reindexer.metrics;

import reactor.core.publisher.Mono;

/** Prepared exports. Flush before atomically recording their commit ID and metrics in the coordinator. */
public interface WorkItemMetricsSink extends AutoCloseable {

    Mono<Void> write(WorkItemMetricsRecord metricsRecord);

    Mono<Void> flush();

    String getLocation();

    @Override
    void close();
}
