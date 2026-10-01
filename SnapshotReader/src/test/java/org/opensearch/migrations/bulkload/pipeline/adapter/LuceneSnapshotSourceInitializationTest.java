package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.SnapshotExtractor;
import org.opensearch.migrations.bulkload.common.DeltaMode;
import org.opensearch.migrations.bulkload.common.DocumentChangeType;
import org.opensearch.migrations.bulkload.common.LuceneDocumentChange;
import org.opensearch.migrations.bulkload.common.TestResources;
import org.opensearch.migrations.bulkload.models.ShardMetadata;
import org.opensearch.migrations.bulkload.pipeline.DocumentMigrationPipeline;
import org.opensearch.migrations.bulkload.pipeline.PipelineException;
import org.opensearch.migrations.bulkload.pipeline.model.BatchResult;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.pipeline.sink.DocumentSink;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Isolated("Temporarily replaces Reactor's shared bounded-elastic scheduler")
class LuceneSnapshotSourceInitializationTest {
    private static final String INDEX = "test_updates_deletes";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final EsShardPartition PARTITION = new EsShardPartition("current", INDEX, 0);

    @TempDir
    private Path workDir;
    private Schedulers.Snapshot schedulerSnapshot;

    @BeforeEach
    void constrainSharedScheduler() {
        var firstPool = new AtomicBoolean(true);
        schedulerSnapshot = Schedulers.setFactoryWithSnapshot(new Schedulers.Factory() {
            @Override
            public Scheduler newBoundedElastic(int threadCap, int queuedTaskCap, ThreadFactory factory, int ttlSeconds) {
                return Schedulers.Factory.super.newBoundedElastic(
                    firstPool.getAndSet(false) ? 1 : threadCap, queuedTaskCap, factory, ttlSeconds);
            }
        });
        // Constrain only the shared pool, leaving independently owned schedulers unchanged.
        Schedulers.boundedElastic();
    }

    @AfterEach
    void restoreSchedulers() {
        Schedulers.resetFrom(schedulerSnapshot);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void migratesRealSnapshotWithOneSharedWorker(boolean delta) {
        var snapshot = TestResources.SNAPSHOT_ES_6_8;
        var extractor = spy(SnapshotExtractor.forLocalSnapshot(snapshot.dir, Version.fromString("ES 6.8")));
        var builder = LuceneSnapshotSource.builder(extractor, snapshot.name, workDir);
        if (delta) {
            // Identical commits still unpack both snapshots before comparing their documents.
            builder.delta(snapshot.name, DeltaMode.UPDATES_AND_DELETES, () -> mock(IRfsContexts.IDeltaStreamContext.class));
        }
        try (var source = builder.build()) {
            var partition = new EsShardPartition(snapshot.name, INDEX, 0);
            assertNotNull(source.readDocuments(partition, 0));
            verifyNoInteractions(extractor);

            var written = new ArrayList<Document>();
            var pipeline = pipeline(source, written);

            // This uses the real adapter, extractor, unpacker and pipeline; only the sink is mocked.
            // Sharing the initialization worker with the unpacker deadlocks even on a local fixture.
            var cursors = pipeline.migratePartition(partition, INDEX, 0).collectList().block(TIMEOUT);
            assertNotNull(cursors);
            assertEquals(delta ? 0 : 3, written.size());
            assertEquals(delta ? 0 : 3, cursors.stream().mapToLong(cursor -> cursor.docsInBatch()).sum());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reusesBlockingInitializationWorkerAndDisposesItOnClose(boolean delta) throws InterruptedException {
        var threads = new ArrayList<Thread>();
        var cleanups = new AtomicInteger();
        try (var source = stubSource(delta, () -> {
            threads.add(Thread.currentThread());
            assertFalse(Schedulers.isInNonBlockingThread());
            return Flux.using(Object::new, resource -> Flux.just(document()), resource -> cleanups.incrementAndGet());
        })) {
            var documents = source.readDocuments(PARTITION, 0);
            assertTrue(threads.isEmpty(), "Creating the publisher must not initialize the shard");
            assertEquals("document", documents.single().block(TIMEOUT).id());
            assertEquals("document", documents.single().block(TIMEOUT).id());
            assertEquals(2, cleanups.get(), "Each subscription must close its reader resource");
            assertEquals(2, threads.size());
            assertSame(threads.get(0), threads.get(1), "The source must reuse its initialization scheduler");

            source.close();
            threads.get(0).join(TIMEOUT.toMillis());
            assertFalse(threads.get(0).isAlive(), "Closing the source must stop its owned worker");
            assertThrows(RejectedExecutionException.class, () -> documents.single().block(TIMEOUT));
            assertEquals(2, threads.size(), "A closed source must not initialize another reader");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationInterruptsInitializationAndAllowsAnotherRead(boolean delta) throws InterruptedException {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var attempts = new AtomicInteger();
        try (var source = stubSource(delta, () -> {
            if (attempts.getAndIncrement() == 0) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    interrupted.countDown();
                }
            }
            return Flux.just(document());
        })) {
            var subscription = source.readDocuments(PARTITION, 0).subscribe();
            try {
                assertTrue(entered.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
                subscription.dispose();
                assertTrue(interrupted.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    "Cancelling the read must interrupt blocking initialization");
                assertEquals("document", source.readDocuments(PARTITION, 0).single().block(TIMEOUT).id());
            } finally {
                release.countDown();
                subscription.dispose();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closingSourceInterruptsActiveInitialization(boolean delta) throws InterruptedException {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        try (var source = stubSource(delta, () -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                interrupted.countDown();
            }
            return Flux.empty();
        })) {
            var subscription = source.readDocuments(PARTITION, 0).subscribe();
            try {
                assertTrue(entered.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
                source.close();
                assertTrue(interrupted.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
                assertThrows(RejectedExecutionException.class,
                    () -> source.readDocuments(PARTITION, 0).single().block(TIMEOUT));
            } finally {
                release.countDown();
                subscription.dispose();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void initializationFailureKeepsItsCauseAndCanBeRetried(boolean delta) {
        var failure = new IllegalStateException("Cannot initialize snapshot");
        var attempts = new AtomicInteger();
        try (var source = stubSource(delta, () -> {
            if (attempts.getAndIncrement() == 0) {
                throw failure;
            }
            return Flux.just(document());
        })) {
            var written = new ArrayList<Document>();
            var migration = pipeline(source, written).migratePartition(PARTITION, INDEX, 0);
            var thrown = assertThrows(PipelineException.class, () -> migration.collectList().block(TIMEOUT));
            assertSame(failure, thrown.getCause());
            assertTrue(written.isEmpty());
            var cursors = migration.collectList().block(TIMEOUT);
            assertEquals(1, cursors.size());
            assertEquals(1, cursors.get(0).lastDocProcessed());
            assertEquals(List.of("document"), written.stream().map(Document::id).toList());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellingDocumentStreamClosesReaderResource(boolean delta) throws InterruptedException {
        var cleanedUp = new CountDownLatch(1);
        try (var source = stubSource(delta, () -> Flux.using(Object::new,
            resource -> Flux.just(document()).concatWith(Flux.never()), resource -> cleanedUp.countDown()))) {
            var documents = source.readDocuments(PARTITION, 0).take(1).collectList().block(TIMEOUT);
            assertEquals(List.of("document"), documents.stream().map(Document::id).toList());
            assertTrue(cleanedUp.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                "Cancelling the document stream must close its reader resource");
        }
    }

    private LuceneSnapshotSource stubSource(boolean delta, Supplier<Flux<LuceneDocumentChange>> initialize) {
        var extractor = mock(SnapshotExtractor.class);
        var current = new SnapshotExtractor.ShardEntry("current", INDEX, "index-id", 0, mock(ShardMetadata.class));
        when(extractor.listIndices("current")).thenReturn(List.of(INDEX));
        when(extractor.listShards("current", INDEX)).thenReturn(List.of(current));
        var builder = LuceneSnapshotSource.builder(extractor, "current", workDir);
        if (delta) {
            var previous = new SnapshotExtractor.ShardEntry("previous", INDEX, "index-id", 0, mock(ShardMetadata.class));
            when(extractor.listSnapshots()).thenReturn(List.of("previous", "current"));
            when(extractor.listIndices("previous")).thenReturn(List.of(INDEX));
            when(extractor.listShards("previous", INDEX)).thenReturn(List.of(previous));
            when(extractor.readDeltaDocuments(eq(current), eq(previous), eq(DeltaMode.UPDATES_ONLY), eq(workDir), any()))
                // Delta reader initialization occurs when its returned publisher is subscribed.
                .thenAnswer(invocation -> Flux.defer(initialize));
            builder.delta("previous", DeltaMode.UPDATES_ONLY, () -> mock(IRfsContexts.IDeltaStreamContext.class));
        } else {
            when(extractor.readDocuments(eq(current), eq(workDir), eq(0), isNull(), eq(false)))
                .thenAnswer(invocation -> initialize.get());
        }
        return builder.build();
    }

    private static DocumentMigrationPipeline pipeline(LuceneSnapshotSource source, List<Document> written) {
        var sink = mock(DocumentSink.class);
        when(sink.writeBatch(eq(INDEX), anyList())).thenAnswer(invocation -> {
            List<Document> batch = invocation.getArgument(1);
            written.addAll(batch);
            return Mono.just(new BatchResult(batch.size(), batch.stream().mapToLong(Document::sourceLength).sum()));
        });
        return new DocumentMigrationPipeline(source, sink, 100, 1024 * 1024);
    }

    private static LuceneDocumentChange document() {
        return new LuceneDocumentChange(0, "document", null, new byte[] {'{', '}'}, null, DocumentChangeType.INDEX);
    }
}
