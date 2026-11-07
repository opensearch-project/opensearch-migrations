package org.opensearch.migrations.bulkload.pipeline;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.pipeline.adapter.EsShardPartition;
import org.opensearch.migrations.bulkload.pipeline.model.BatchResult;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.pipeline.model.PositionedDocument;
import org.opensearch.migrations.bulkload.pipeline.sink.DocumentSink;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class DocumentMigrationPipelineBatchingTest {
    private static final int MIB = 1024 * 1024;

    static Stream<Arguments> examples() {
        return Stream.of(
            Arguments.of(new int[] {9, 9, 9, 9}, List.of(1, 1, 1, 1), List.of(9L, 9L, 9L, 9L)),
            Arguments.of(new int[] {1, 1, 1, 98}, List.of(3, 1), List.of(3L, 98L)),
            Arguments.of(new int[] {1, 1, 1, 101}, List.of(3, 1), List.of(3L, 101L)),
            Arguments.of(new int[] {26, 26, 26, 26}, List.of(1, 1, 1, 1), List.of(26L, 26L, 26L, 26L)),
            Arguments.of(new int[] {101, 1, 1, 1}, List.of(1, 3), List.of(101L, 3L)),
            Arguments.of(new int[] {1, 1, 1, 8}, List.of(3, 1), List.of(3L, 8L)),
            Arguments.of(new int[] {5, 5, 5}, List.of(2, 1), List.of(10L, 5L)),
            Arguments.of(new int[] {1, 11, 1}, List.of(1, 1, 1), List.of(1L, 11L, 1L))
        );
    }

    @ParameterizedTest
    @MethodSource("examples")
    void respectsDefaultByteLimitExceptForSingleOversizedDocuments(
        int[] sourceMiB, List<Integer> expectedCounts, List<Long> expectedMiB
    ) {
        var partition = new EsShardPartition("snapshot", "index", 0);
        var documents = new ArrayList<PositionedDocument>();
        for (int i = 0; i < sourceMiB.length; i++) {
            // Valid JSON with an exactly sized ASCII string value.
            var bytes = new byte[sourceMiB[i] * MIB];
            Arrays.fill(bytes, (byte) 'x');
            var prefix = "{\"data\":\"".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            System.arraycopy(prefix, 0, bytes, 0, prefix.length);
            bytes[bytes.length - 2] = '"';
            bytes[bytes.length - 1] = '}';
            documents.add(new PositionedDocument(
                new Document("doc-" + i, bytes, Document.Operation.UPSERT, Map.of(), Map.of()), String.valueOf(i + 1)));
        }

        var counts = new CopyOnWriteArrayList<Integer>();
        var sizes = new CopyOnWriteArrayList<Long>();
        var writtenIds = new CopyOnWriteArrayList<String>();
        var source = Mockito.mock(DocumentSource.class);
        var sink = Mockito.mock(DocumentSink.class);
        Mockito.when(source.readDocuments(partition, null)).thenReturn(Flux.fromIterable(documents));
        Mockito.when(sink.writeBatch(Mockito.eq("index"), Mockito.anyList())).thenAnswer(invocation -> {
            List<PositionedDocument> batch = invocation.getArgument(1);
            long bytes = batch.stream().mapToLong(d -> d.document().sourceLength()).sum();
            counts.add(batch.size());
            sizes.add(bytes / MIB);
            writtenIds.addAll(batch.stream().map(d -> d.document().id()).toList());
            Assertions.assertTrue(batch.size() == 1 || bytes <= 10L * MIB);
            return Mono.just(new BatchResult(batch.size(), bytes, batch.get(batch.size() - 1).cursorAfter()));
        });

        new DocumentMigrationPipeline(source, sink, Integer.MAX_VALUE, 10L * MIB)
            .migratePartition(partition, "index", null)
            .blockLast(Duration.ofSeconds(20));

        Assertions.assertEquals(expectedCounts, counts);
        Assertions.assertEquals(expectedMiB, sizes);
        Assertions.assertEquals(documents.stream().map(d -> d.document().id()).toList(), writtenIds);
    }
}
