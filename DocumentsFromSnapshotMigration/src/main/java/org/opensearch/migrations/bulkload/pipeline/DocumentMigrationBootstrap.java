package org.opensearch.migrations.bulkload.pipeline;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.opensearch.migrations.bulkload.SnapshotExtractor;
import org.opensearch.migrations.bulkload.common.DeltaMode;
import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.RfsException;
import org.opensearch.migrations.bulkload.lucene.FieldMappingContext;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.pipeline.adapter.EsShardPartition;
import org.opensearch.migrations.bulkload.pipeline.adapter.LuceneSnapshotSource;
import org.opensearch.migrations.bulkload.pipeline.adapter.OpenSearchDocumentSink;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.bulkload.workcoordination.IWorkCoordinator;
import org.opensearch.migrations.bulkload.workcoordination.ScopedWorkCoordinator;
import org.opensearch.migrations.bulkload.workcoordination.WorkItemTimeProvider;
import org.opensearch.migrations.bulkload.worker.CompletionStatus;
import org.opensearch.migrations.bulkload.worker.WorkItemCursor;
import org.opensearch.migrations.reindexer.tracing.IDocumentMigrationContexts;
import org.opensearch.migrations.transform.IJsonTransformer;

import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import reactor.core.scheduler.Schedulers;

/**
 * Top-level wiring for the clean pipeline approach. Connects all adapters and provides
 * work-coordinated execution for horizontal scaling.
 *
 * <pre>
 * var runner = DocumentMigrationBootstrap.builder()
 *     .extractor(snapshotExtractor)
 *     .targetClient(openSearchClient)
 *     .snapshotName("my-snapshot")
 *     .workDir(Paths.get("/tmp/lucene"))
 *     .workCoordinator(scopedWorkCoordinator)
 *     .maxInitialLeaseDuration(Duration.ofMinutes(10))
 *     .cursorConsumer(progressCursor::set)
 *     .cancellationTriggerConsumer(cancellationRef::set)
 *     .build();
 * runner.migrateOneShard(contextSupplier);
 * </pre>
 */
@Slf4j
@Builder
@SuppressWarnings("java:S1170") // Builder.Default fields are instance-level, not static
public class DocumentMigrationBootstrap {

    private final SnapshotExtractor extractor;
    private final OpenSearchClient targetClient;
    private final String snapshotName;
    private final Path workDir;

    private final int maxDocsPerBatch;
    private final long maxBytesPerBatch;
    private final int batchConcurrency;

    // Optional: document transformation
    @Builder.Default
    private final Supplier<IJsonTransformer> transformerSupplier = null;
    @Builder.Default
    private final boolean allowServerGeneratedIds = false;
    @Builder.Default
    private final DocumentExceptionAllowlist allowlist = DocumentExceptionAllowlist.empty();

    // Optional: shard size limit (0 = no limit)
    @Builder.Default
    private final long maxShardSizeBytes = 0;

    // Optional: delta snapshot support
    @Builder.Default
    private final String previousSnapshotName = null;
    @Builder.Default
    private final DeltaMode deltaMode = null;
    @Builder.Default
    private final Supplier<IRfsContexts.IDeltaStreamContext> deltaContextFactory = null;

    // Optional: external document source (e.g. Solr). When set, bypasses LuceneSnapshotSource.
    @Builder.Default
    private final DocumentSource externalDocumentSource = null;

    // Sourceless migration support: when true and index has _source disabled,
    // reconstructs documents from stored fields and doc_values
    @Builder.Default
    private final boolean enableSourcelessMigrations = false;

    // When true, treat _recovery_source as _source if present (ES 7+ / OpenSearch soft-deletes)
    @Builder.Default
    private final boolean useRecoverySource = false;

    // Index metadata factory for reading mappings (needed for sourceless migration)
    @Builder.Default
    private final IndexMetadata.Factory indexMetadataFactory = null;

    // Optional: work coordination
    @Builder.Default
    private final ScopedWorkCoordinator workCoordinator = null;
    @Builder.Default
    private final WorkItemTimeProvider workItemTimeProvider = null;
    @Builder.Default
    private final Duration maxInitialLeaseDuration = Duration.ofMinutes(10);
    @Builder.Default
    private final Consumer<WorkItemCursor> cursorConsumer = cursor -> {};
    @Builder.Default
    private final Consumer<Runnable> cancellationTriggerConsumer = runnable -> {};

    /**
     * Acquire and migrate a single shard via work coordination.
     * Each JVM invocation processes one work item, then exits so the
     * orchestrator can restart the process for the next shard.
     *
     * @return WORK_COMPLETED if a shard was migrated, NOTHING_DONE if no work was available
     */
    public CompletionStatus migrateOneShard(
        Supplier<IDocumentMigrationContexts.IDocumentReindexContext> contextSupplier
    ) throws IOException, InterruptedException {
        if (workCoordinator == null) {
            throw new IllegalStateException("workCoordinator must be set for coordinated migration");
        }
        var source = createDocumentSource();
        var contextRef = new AtomicReference<IDocumentMigrationContexts.IDocumentReindexContext>();
        var sink = new OpenSearchDocumentSink(
            targetClient, transformerSupplier, allowServerGeneratedIds, allowlist,
            () -> {
                var ctx = contextRef.get();
                return ctx != null ? ctx.createBulkRequest() : null;
            }
        );
        try {
            var pipelineConfig = new PipelineConfig(source, sink, maxDocsPerBatch, maxBytesPerBatch, batchConcurrency);
            Supplier<IDocumentMigrationContexts.IDocumentReindexContext> wrappedContextSupplier = () -> {
                var ctx = contextSupplier.get();
                contextRef.set(ctx);
                return ctx;
            };
            try (var context = wrappedContextSupplier.get()) {
                return workCoordinator.ensurePhaseCompletion(wc -> {
                    try {
                        return wc.acquireNextWorkItem(maxInitialLeaseDuration, context::createOpeningContext);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }, new IWorkCoordinator.WorkAcquisitionOutcomeVisitor<>() {
                    @Override
                    public CompletionStatus onAlreadyCompleted() {
                        return CompletionStatus.NOTHING_DONE;
                    }

                    @Override
                    public CompletionStatus onAcquiredWork(IWorkCoordinator.WorkItemAndDuration workItem) {
                        return runPartitionMigration(workItem, pipelineConfig, context);
                    }

                    @Override
                    public CompletionStatus onNoAvailableWorkToBeDone() {
                        return CompletionStatus.NOTHING_DONE;
                    }
                }, context::createCloseContext);
            }
        } finally {
            closeQuietly(source);
            closeQuietly(sink);
        }
    }

    private CompletionStatus runPartitionMigration(
        IWorkCoordinator.WorkItemAndDuration workItem,
        PipelineConfig pipelineConfig,
        IDocumentMigrationContexts.IDocumentReindexContext context
    ) {
        var wi = workItem.getWorkItem();
        log.info("Pipeline acquired work item: {}", wi);

        if (workItemTimeProvider != null) {
            workItemTimeProvider.getLeaseAcquisitionTimeRef().set(Instant.now());
        }

        var partition = resolvePartition(wi);
        long startingOffset = wi.getStartingDocId() != null && wi.getStartingDocId() >= 0
            ? wi.getStartingDocId() : 0;

        var pipeline = new DocumentMigrationPipeline(
            pipelineConfig.source(), pipelineConfig.sink(),
            pipelineConfig.maxDocsPerBatch(), pipelineConfig.maxBytesPerBatch(),
            1, pipelineConfig.batchConcurrency()
        );
        var progressMonitor = new PipelineProgressMonitor(pipeline);
        progressMonitor.start();
        var latch = new CountDownLatch(1);
        var batchCount = new AtomicInteger();
        var totalDocsMigrated = new AtomicLong();
        var totalBytesMigrated = new AtomicLong();
        var migrationError = new AtomicReference<Throwable>();
        var finishScheduler = Schedulers.newSingle("pipelineFinishScheduler");

        var disposable = pipeline.migratePartition(partition, wi.getIndexName(), startingOffset)
            .subscribeOn(finishScheduler)
            .doFirst(() -> {
                if (workItemTimeProvider != null) {
                    workItemTimeProvider.getDocumentMigraionStartTimeRef().set(Instant.now());
                }
            })
            .doFinally(s -> finishScheduler.dispose())
            .subscribe(
                cursor -> {
                    batchCount.incrementAndGet();
                    totalDocsMigrated.addAndGet(cursor.docsInBatch());
                    totalBytesMigrated.addAndGet(cursor.bytesInBatch());
                    cursorConsumer.accept(new WorkItemCursor(cursor.lastDocProcessed()));
                },
                error -> {
                    log.atError()
                        .setCause(error)
                        .setMessage("Pipeline error for {}")
                        .addArgument(wi)
                        .log();
                    migrationError.set(error);
                    latch.countDown();
                },
                () -> {
                    log.info("Pipeline completed for index={}, shard={}", wi.getIndexName(), wi.getShardNumber());
                    latch.countDown();
                }
            );

        cancellationTriggerConsumer.accept(disposable::dispose);

        try {
            long start = System.currentTimeMillis();
            latch.await();
            long durationMs = System.currentTimeMillis() - start;
            log.atInfo()
                .setMessage("Partition migration stats: index={}, shard={}, docs={}, bytes={}, batches={}, duration={}ms")
                .addArgument(wi.getIndexName())
                .addArgument(wi.getShardNumber())
                .addArgument(totalDocsMigrated::get)
                .addArgument(totalBytesMigrated::get)
                .addArgument(batchCount::get)
                .addArgument(durationMs)
                .log();

            var error = migrationError.get();
            if (error != null) {
                context.recordPipelineError();
                throw new RfsException("Partition migration failed for " + wi, error);
            }
            context.recordShardDuration(durationMs);
            context.recordDocsMigrated(totalDocsMigrated.get());
            context.recordBytesMigrated(totalBytesMigrated.get());
            return CompletionStatus.WORK_COMPLETED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RfsException("Partition migration interrupted", e);
        } finally {
            progressMonitor.close();
        }
    }

    /**
     * Maps a work item to the appropriate Partition. For external sources (e.g. Solr),
     * looks up the partition from the source's listPartitions by shard index.
     */
    private org.opensearch.migrations.bulkload.pipeline.model.Partition resolvePartition(
            IWorkCoordinator.WorkItemAndDuration.WorkItem wi) {
        if (externalDocumentSource != null) {
            var partitions = externalDocumentSource.listPartitions(wi.getIndexName());
            int shardIdx = wi.getShardNumber();
            if (shardIdx < partitions.size()) {
                return partitions.get(shardIdx);
            }
            throw new IllegalStateException("Shard index " + shardIdx + " out of range for " +
                wi.getIndexName() + " (has " + partitions.size() + " partitions)");
        }
        return new EsShardPartition(snapshotName, wi.getIndexName(), wi.getShardNumber());
    }

    private DocumentSource createDocumentSource() {
        if (externalDocumentSource != null) {
            return externalDocumentSource;
        }
        var builder = LuceneSnapshotSource.builder(extractor, snapshotName, workDir)
            .maxShardSizeBytes(maxShardSizeBytes)
            .useRecoverySource(useRecoverySource);
        if (previousSnapshotName != null && deltaMode != null) {
            log.info("Creating delta document source: previous={}, mode={}", previousSnapshotName, deltaMode);
            builder.delta(previousSnapshotName, deltaMode, deltaContextFactory);
        }
        if (enableSourcelessMigrations && indexMetadataFactory != null) {
            log.info("Sourceless migrations enabled — will reconstruct _source from stored fields/doc_values");
            // Optional wraps nullable FieldMappingContext so computeIfAbsent caches the
            // "no reconstruction needed" result too. Returning null from the remapping
            // function is a no-op for ConcurrentHashMap storage, so a plain
            // Map<String, FieldMappingContext> would re-read snapshot metadata on every
            // provider invocation for _source-enabled indices.
            Map<String, Optional<FieldMappingContext>> cache = new ConcurrentHashMap<>();
            builder.sourcelessMappingContextProvider(indexName -> {
                return cache.computeIfAbsent(indexName, name -> {
                    try {
                        var meta = indexMetadataFactory.fromRepo(snapshotName, name);
                        if (!meta.needsSourceReconstruction()) {
                            log.debug("Index {} has full _source enabled, no reconstruction needed", name);
                            return Optional.empty();
                        }
                        log.info("Index {} needs source reconstruction (disabled={}, partial={}), building FieldMappingContext",
                            name, !meta.isSourceEnabled(), meta.isSourcePartial());
                        return Optional.of(new FieldMappingContext(meta.getMappings()));
                    } catch (Exception e) {
                        throw new RuntimeException(
                            "Failed to read metadata for index " + name +
                            " — cannot determine if source reconstruction is needed", e);
                    }
                }).orElse(null);
            });
        }
        return builder.build();
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            log.warn("Error closing resource", e);
        }
    }
}
