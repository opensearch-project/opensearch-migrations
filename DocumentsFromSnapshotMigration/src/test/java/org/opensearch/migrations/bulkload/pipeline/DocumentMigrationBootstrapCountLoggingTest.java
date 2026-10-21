package org.opensearch.migrations.bulkload.pipeline;

import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.pipeline.adapter.EsShardPartition;
import org.opensearch.migrations.bulkload.pipeline.sink.DocumentSink;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;
import org.opensearch.migrations.bulkload.workcoordination.IWorkCoordinator.WorkItemAndDuration;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.testutils.CloseableLogSetup;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentMigrationBootstrapCountLoggingTest {
    static Stream<OptionalLong> counts() {
        return Stream.of(OptionalLong.empty(), OptionalLong.of(0), OptionalLong.of(14));
    }

    @ParameterizedTest
    @MethodSource("counts")
    void logsKnownCountsAndUnknownDistinctly(OptionalLong count) {
        var partition = new EsShardPartition("snap", "idx", 0);
        var source = mock(DocumentSource.class);
        when(source.listPartitions("idx")).thenReturn(List.of(partition));
        when(source.readDocuments(partition, 0)).thenReturn(Flux.empty());
        when(source.countLiveDocuments(partition)).thenReturn(count);
        var bootstrap = DocumentMigrationBootstrap.builder()
            .documentSource(source).targetClient(mock(OpenSearchClient.class)).build();
        try (var logs = new CloseableLogSetup(DocumentMigrationBootstrap.class.getName());
             var context = DocumentMigrationTestContext.factory().noOtelTracking().createReindexContext()) {
            bootstrap.runPartitionMigration(
                new WorkItemAndDuration(Instant.now().plusSeconds(60),
                    new WorkItemAndDuration.WorkItem("idx", 0, 0L)),
                new PipelineConfig(source, mock(DocumentSink.class), 10, 1000, 1), context);
            var expected = count.isPresent() ? Long.toString(count.getAsLong()) : "unknown";
            assertTrue(logs.getLogEvents().stream().anyMatch(line ->
                line.contains("Partition migration stats:") && line.contains("sourcePartition=snap/idx/0")
                    && line.contains("shardLiveLuceneDocs=" + expected)));
        }
    }
}
