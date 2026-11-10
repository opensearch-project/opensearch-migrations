package org.opensearch.migrations.reindexer.tracing;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.pipeline.PipelineMetrics;
import org.opensearch.migrations.tracing.CommonScopedMetricInstruments;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;

/**
 * Process-local pipeline instruments. Attributes are bounded stage/limit/outcome sets; document, index, shard, and
 * worker identifiers are deliberately absent.
 */
public class PipelineInstruments implements PipelineMetrics {
    private static final String COUNT_UNIT = "count";
    private static final String BYTES_UNIT = "bytes";

    public static final String PIPELINE_STAGE_DOCUMENTS = "pipelineStageDocuments";
    public static final String PIPELINE_STAGE_BYTES = "pipelineStageBytes";
    public static final String PIPELINE_PREPARATION_QUEUE_WAIT = "pipelinePreparationQueueWait";
    public static final String PIPELINE_PREPARATION_DURATION = "pipelinePreparationDuration";
    public static final String PIPELINE_PREPARATION_ORDERED_WAIT = "pipelinePreparationOrderedWait";
    public static final String PIPELINE_PACKING_DURATION = "pipelinePackingDuration";
    public static final String PIPELINE_REQUEST_QUEUE_WAIT = "pipelineRequestQueueWait";
    public static final String PIPELINE_REQUEST_GROUP_DURATION = "pipelineRequestGroupDuration";
    public static final String PIPELINE_PACKED_BATCH_DOCUMENTS = "pipelinePackedBatchDocuments";
    public static final String PIPELINE_PACKED_BATCH_BYTES = "pipelinePackedBatchBytes";
    public static final String PIPELINE_PACKED_BATCH_FILL = "pipelinePackedBatchFill";
    public static final String PIPELINE_PREPARATION_QUEUED_CHUNKS = "pipelinePreparationQueuedChunks";
    public static final String PIPELINE_PREPARATION_QUEUED_DOCUMENTS = "pipelinePreparationQueuedDocuments";
    public static final String PIPELINE_PREPARATION_QUEUED_BYTES = "pipelinePreparationQueuedBytes";
    public static final String PIPELINE_PREPARATION_ACTIVE_CHUNKS = "pipelinePreparationActiveChunks";
    public static final String PIPELINE_PREPARATION_ORDERED_CHUNKS = "pipelinePreparationOrderedChunks";
    public static final String PIPELINE_PREPARATION_ORDERED_DOCUMENTS = "pipelinePreparationOrderedDocuments";
    public static final String PIPELINE_PREPARATION_ORDERED_BYTES = "pipelinePreparationOrderedBytes";
    public static final String PIPELINE_REQUEST_READY_BATCHES = "pipelineRequestReadyBatches";
    public static final String PIPELINE_REQUEST_READY_DOCUMENTS = "pipelineRequestReadyDocuments";
    public static final String PIPELINE_REQUEST_READY_BYTES = "pipelineRequestReadyBytes";
    public static final String PIPELINE_REQUEST_ACTIVE_GROUPS = "pipelineRequestActiveGroups";
    public static final String PIPELINE_MAX_BATCH_DOCUMENTS = "pipelineMaxBatchDocuments";
    public static final String PIPELINE_MAX_BATCH_BYTES = "pipelineMaxBatchBytes";
    public static final String PIPELINE_BATCH_CONCURRENCY = "pipelineBatchConcurrency";
    public static final String PIPELINE_PREPARATION_CONCURRENCY = "pipelinePreparationConcurrency";
    public static final String PIPELINE_PREPARATION_CHUNK_DOCUMENTS = "pipelinePreparationChunkDocuments";

    public static final AttributeKey<String> STAGE = AttributeKey.stringKey("stage");
    public static final AttributeKey<String> LIMIT = AttributeKey.stringKey("limit");
    public static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("outcome");

    static final List<String> STAGES = List.of(
        "source",
        "prepared",
        "ordered_prepared",
        "packed",
        "request_started",
        "request_completed",
        "checkpointed"
    );
    static final List<Long> DOCUMENT_BUCKETS =
        List.of(1L, 10L, 100L, 500L, 1_000L, 2_500L, 5_000L, 10_000L, 20_000L, 50_000L);
    static final List<Long> BYTE_BUCKETS =
        Stream.iterate(1024L, value -> value <= 256L * 1024 * 1024, value -> value * 4).toList();
    static final List<Double> FILL_BUCKETS =
        List.of(0.0, 10.0, 25.0, 50.0, 75.0, 90.0, 95.0, 99.0, 100.0, 110.0, 125.0, 200.0);

    private final LongCounter stageDocuments;
    private final LongCounter stageBytes;
    private final DoubleHistogram preparationQueueWait;
    private final DoubleHistogram preparationDuration;
    private final DoubleHistogram preparationOrderedWait;
    private final DoubleHistogram packingDuration;
    private final DoubleHistogram requestQueueWait;
    private final DoubleHistogram requestGroupDuration;
    private final LongHistogram packedBatchDocuments;
    private final LongHistogram packedBatchBytes;
    private final DoubleHistogram packedBatchFill;

    private final AtomicLong preparationQueuedChunks = new AtomicLong();
    private final AtomicLong preparationQueuedDocuments = new AtomicLong();
    private final AtomicLong preparationQueuedBytes = new AtomicLong();
    private final AtomicLong preparationActiveChunks = new AtomicLong();
    private final AtomicLong preparationOrderedChunks = new AtomicLong();
    private final AtomicLong preparationOrderedDocuments = new AtomicLong();
    private final AtomicLong preparationOrderedBytes = new AtomicLong();
    private final AtomicLong requestReadyBatches = new AtomicLong();
    private final AtomicLong requestReadyDocuments = new AtomicLong();
    private final AtomicLong requestReadyBytes = new AtomicLong();
    private final AtomicLong requestActiveGroups = new AtomicLong();
    private final AtomicLong maxBatchDocuments = new AtomicLong();
    private final AtomicLong maxBatchBytes = new AtomicLong();
    private final AtomicLong batchConcurrency = new AtomicLong();
    private final AtomicLong preparationConcurrency = new AtomicLong();
    private final AtomicLong preparationChunkDocuments = new AtomicLong();

    // The SDK owns the callbacks; retaining the handles keeps their lifecycle tied to this process instrument set.
    @SuppressWarnings("unused")
    private final List<AutoCloseable> gauges;

    public PipelineInstruments(Meter meter) {
        stageDocuments = meter.counterBuilder(PIPELINE_STAGE_DOCUMENTS).setUnit(COUNT_UNIT).build();
        stageBytes = meter.counterBuilder(PIPELINE_STAGE_BYTES).setUnit(BYTES_UNIT).build();
        preparationQueueWait = durationHistogram(meter, PIPELINE_PREPARATION_QUEUE_WAIT);
        preparationDuration = durationHistogram(meter, PIPELINE_PREPARATION_DURATION);
        preparationOrderedWait = longDurationHistogram(meter, PIPELINE_PREPARATION_ORDERED_WAIT);
        packingDuration = durationHistogram(meter, PIPELINE_PACKING_DURATION);
        requestQueueWait = longDurationHistogram(meter, PIPELINE_REQUEST_QUEUE_WAIT);
        requestGroupDuration = longDurationHistogram(meter, PIPELINE_REQUEST_GROUP_DURATION);
        packedBatchDocuments = meter.histogramBuilder(PIPELINE_PACKED_BATCH_DOCUMENTS).setUnit(COUNT_UNIT).ofLongs()
            .setExplicitBucketBoundariesAdvice(DOCUMENT_BUCKETS).build();
        packedBatchBytes = meter.histogramBuilder(PIPELINE_PACKED_BATCH_BYTES).setUnit(BYTES_UNIT).ofLongs()
            .setExplicitBucketBoundariesAdvice(BYTE_BUCKETS).build();
        packedBatchFill = meter.histogramBuilder(PIPELINE_PACKED_BATCH_FILL).setUnit("percent")
            .setExplicitBucketBoundariesAdvice(FILL_BUCKETS).build();

        STAGES.forEach(stage -> {
            var attributes = Attributes.of(STAGE, stage);
            stageDocuments.add(0, attributes);
            stageBytes.add(0, attributes);
        });

        var registered = new ArrayList<AutoCloseable>();
        registerGauge(meter, registered, PIPELINE_PREPARATION_QUEUED_CHUNKS, COUNT_UNIT, preparationQueuedChunks);
        registerGauge(meter, registered, PIPELINE_PREPARATION_QUEUED_DOCUMENTS, COUNT_UNIT,
            preparationQueuedDocuments);
        registerGauge(meter, registered, PIPELINE_PREPARATION_QUEUED_BYTES, BYTES_UNIT, preparationQueuedBytes);
        registerGauge(meter, registered, PIPELINE_PREPARATION_ACTIVE_CHUNKS, COUNT_UNIT, preparationActiveChunks);
        registerGauge(meter, registered, PIPELINE_PREPARATION_ORDERED_CHUNKS, COUNT_UNIT, preparationOrderedChunks);
        registerGauge(meter, registered, PIPELINE_PREPARATION_ORDERED_DOCUMENTS, COUNT_UNIT,
            preparationOrderedDocuments);
        registerGauge(meter, registered, PIPELINE_PREPARATION_ORDERED_BYTES, BYTES_UNIT, preparationOrderedBytes);
        registerGauge(meter, registered, PIPELINE_REQUEST_READY_BATCHES, COUNT_UNIT, requestReadyBatches);
        registerGauge(meter, registered, PIPELINE_REQUEST_READY_DOCUMENTS, COUNT_UNIT, requestReadyDocuments);
        registerGauge(meter, registered, PIPELINE_REQUEST_READY_BYTES, BYTES_UNIT, requestReadyBytes);
        registerGauge(meter, registered, PIPELINE_REQUEST_ACTIVE_GROUPS, COUNT_UNIT, requestActiveGroups);
        registerGauge(meter, registered, PIPELINE_MAX_BATCH_DOCUMENTS, COUNT_UNIT, maxBatchDocuments);
        registerGauge(meter, registered, PIPELINE_MAX_BATCH_BYTES, BYTES_UNIT, maxBatchBytes);
        registerGauge(meter, registered, PIPELINE_BATCH_CONCURRENCY, COUNT_UNIT, batchConcurrency);
        registerGauge(meter, registered, PIPELINE_PREPARATION_CONCURRENCY, COUNT_UNIT, preparationConcurrency);
        registerGauge(meter, registered, PIPELINE_PREPARATION_CHUNK_DOCUMENTS, COUNT_UNIT,
            preparationChunkDocuments);
        gauges = List.copyOf(registered);
    }

    private static DoubleHistogram durationHistogram(Meter meter, String name) {
        return meter.histogramBuilder(name).setUnit("ms")
            .setExplicitBucketBoundariesAdvice(CommonScopedMetricInstruments.DURATION_BUCKETS_MS).build();
    }

    private static DoubleHistogram longDurationHistogram(Meter meter, String name) {
        return meter.histogramBuilder(name).setUnit("ms")
            .setExplicitBucketBoundariesAdvice(CommonScopedMetricInstruments.LONG_DURATION_BUCKETS_MS).build();
    }

    private static void registerGauge(
        Meter meter,
        List<AutoCloseable> gauges,
        String name,
        String unit,
        AtomicLong value
    ) {
        gauges.add(meter.gaugeBuilder(name).setUnit(unit).ofLongs().buildWithCallback(m -> m.record(value.get())));
    }

    private void addStage(String stage, long documents, long bytes) {
        var attributes = Attributes.of(STAGE, stage);
        stageDocuments.add(documents, attributes);
        stageBytes.add(bytes, attributes);
    }

    private static void subtract(AtomicLong value, long amount) {
        value.updateAndGet(current -> Math.max(0, current - amount));
    }

    @Override
    public void onConfigured(
        long configuredMaxBatchDocuments,
        long configuredMaxBatchBytes,
        long configuredBatchConcurrency,
        long configuredPreparationConcurrency,
        long configuredPreparationChunkDocuments
    ) {
        maxBatchDocuments.set(configuredMaxBatchDocuments);
        maxBatchBytes.set(configuredMaxBatchBytes);
        batchConcurrency.set(configuredBatchConcurrency);
        preparationConcurrency.set(configuredPreparationConcurrency);
        preparationChunkDocuments.set(configuredPreparationChunkDocuments);
    }

    @Override
    public void onPreparationChunkQueued(long documents, long sourceBytes) {
        preparationQueuedChunks.incrementAndGet();
        preparationQueuedDocuments.addAndGet(documents);
        preparationQueuedBytes.addAndGet(sourceBytes);
        addStage("source", documents, sourceBytes);
    }

    @Override
    public void onPreparationChunkStarted(long documents, long sourceBytes, double queueMillis) {
        subtract(preparationQueuedChunks, 1);
        subtract(preparationQueuedDocuments, documents);
        subtract(preparationQueuedBytes, sourceBytes);
        preparationActiveChunks.incrementAndGet();
        preparationQueueWait.record(queueMillis);
    }

    @Override
    public void onPreparationChunkFinished(
        long documents,
        long sourceBytes,
        long preparedBytes,
        double durationMillis,
        boolean successful
    ) {
        subtract(preparationActiveChunks, 1);
        preparationDuration.record(durationMillis, Attributes.of(OUTCOME, successful ? "success" : "failure"));
        if (successful) {
            preparationOrderedChunks.incrementAndGet();
            preparationOrderedDocuments.addAndGet(documents);
            preparationOrderedBytes.addAndGet(preparedBytes);
            addStage("prepared", documents, preparedBytes);
        }
    }

    @Override
    public void onPreparationChunkReleased(long documents, long preparedBytes, double orderedWaitMillis) {
        subtract(preparationOrderedChunks, 1);
        subtract(preparationOrderedDocuments, documents);
        subtract(preparationOrderedBytes, preparedBytes);
        preparationOrderedWait.record(orderedWaitMillis);
        addStage("ordered_prepared", documents, preparedBytes);
    }

    @Override
    public void onPackingChunk(long documents, double durationMillis) {
        packingDuration.record(durationMillis);
    }

    @Override
    public void onBatchPacked(
        long documents,
        long preparedBytes,
        double documentFillPercent,
        double byteFillPercent
    ) {
        requestReadyBatches.incrementAndGet();
        requestReadyDocuments.addAndGet(documents);
        requestReadyBytes.addAndGet(preparedBytes);
        packedBatchDocuments.record(documents);
        packedBatchBytes.record(preparedBytes);
        packedBatchFill.record(documentFillPercent, Attributes.of(LIMIT, "documents"));
        packedBatchFill.record(byteFillPercent, Attributes.of(LIMIT, "bytes"));
        addStage("packed", documents, preparedBytes);
    }

    @Override
    public void onRequestGroupStarted(long batches, long documents, long preparedBytes, double oldestReadyMillis) {
        subtract(requestReadyBatches, batches);
        subtract(requestReadyDocuments, documents);
        subtract(requestReadyBytes, preparedBytes);
        requestActiveGroups.incrementAndGet();
        requestQueueWait.record(oldestReadyMillis);
        addStage("request_started", documents, preparedBytes);
    }

    @Override
    public void onRequestGroupFinished(
        long documents,
        long preparedBytes,
        double durationMillis,
        boolean successful
    ) {
        subtract(requestActiveGroups, 1);
        requestGroupDuration.record(durationMillis, Attributes.of(OUTCOME, successful ? "success" : "failure"));
        if (successful) {
            addStage("request_completed", documents, preparedBytes);
        }
    }

    @Override
    public void onCheckpoint(long documents, long sourceBytes) {
        addStage("checkpointed", documents, sourceBytes);
    }

    @Override
    public void onPipelineClosed() {
        preparationQueuedChunks.set(0);
        preparationQueuedDocuments.set(0);
        preparationQueuedBytes.set(0);
        preparationActiveChunks.set(0);
        preparationOrderedChunks.set(0);
        preparationOrderedDocuments.set(0);
        preparationOrderedBytes.set(0);
        requestReadyBatches.set(0);
        requestReadyDocuments.set(0);
        requestReadyBytes.set(0);
        requestActiveGroups.set(0);
    }
}
