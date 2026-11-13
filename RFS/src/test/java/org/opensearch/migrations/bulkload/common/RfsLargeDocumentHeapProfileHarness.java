package org.opensearch.migrations.bulkload.common;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.bulk.BulkNdjson;
import org.opensearch.migrations.bulkload.common.bulk.IndexOp;
import org.opensearch.migrations.bulkload.common.bulk.operations.IndexOperationMeta;
import org.opensearch.migrations.bulkload.common.http.CompressionMode;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.bulkload.pipeline.DocumentMigrationPipeline;
import org.opensearch.migrations.bulkload.pipeline.PipelineMetrics;
import org.opensearch.migrations.bulkload.pipeline.SourceDocumentMemoryTracker;
import org.opensearch.migrations.bulkload.pipeline.WeightedByteLimiter;
import org.opensearch.migrations.bulkload.pipeline.adapter.EsShardPartition;
import org.opensearch.migrations.bulkload.pipeline.adapter.OpenSearchDocumentSink;
import org.opensearch.migrations.bulkload.pipeline.model.CollectionMetadata;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.pipeline.model.Partition;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;
import org.opensearch.migrations.bulkload.version_os_2_11.OpenSearchClient_OS_2_11;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;
import org.opensearch.migrations.transform.IJsonTransformer;

import com.fasterxml.jackson.databind.ObjectMapper;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Isolated child-JVM profiler for the complete RFS large-document path.
 *
 * <p>The source uses the ordinary source-memory admission callback. The sink is the real
 * {@link OpenSearchDocumentSink}, and the client performs real raw/transformed encoding, request
 * assembly and optional {@link LargeDocumentSplit} processing. Only the final transport is replaced:
 * it retains no request bodies, returns a controlled successful response, and never persists data.
 *
 * <p>The source runs as repeated finite waves of exactly the requested concurrency. Completing each
 * wave flushes RFS's one-document packing lookahead, so concurrency one measures one admitted
 * document and concurrency four measures four without bypassing the ordinary pipeline.
 */
public final class RfsLargeDocumentHeapProfileHarness {
    private static final int MIB = 1024 * 1024;
    private static final int MAX_REQUEST_BYTES = 100 * MIB;
    private static final int PRODUCTION_MAX_DOCS_PER_BATCH = 20_000;
    private static final int VALUE_BYTES = 4096;
    private static final int CONTAINER_COUNT = 512;
    private static final int NESTING_DEPTH = 8;
    private static final int SAMPLING_INTERVAL_MILLIS = 2;
    private static final int TARGET_DELAY_MILLIS = 10;
    private static final int LIVE_PROBE_TARGET_DELAY_MILLIS = 5_000;
    private static final String INDEX = "heap-profile";
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultMapper();
    private static final java.lang.management.MemoryMXBean MEMORY_MX_BEAN =
        ManagementFactory.getMemoryMXBean();

    private RfsLargeDocumentHeapProfileHarness() {}

    public static void main(String[] args) throws Exception {
        var config = Config.parse(args);
        Files.createDirectories(config.outputDirectory());
        var result = run(config);
        result.write(config.outputDirectory().resolve(config.caseName() + "-result.csv"));
        System.out.println("RFS_HEAP_PROFILE_RESULT " + result.toKeyValues());
    }

    private static Result run(Config config) throws Exception {
        Path sourceFile = prepareSource(config);
        int sourceBytes = Math.toIntExact(Files.size(sourceFile));
        long maxTransformBytes = config.sourceMode() == SourceMode.TRANSFORM
            ? calibratedTransformBytes(config.outputBytes())
            : 0;

        forceGcAndSettle();

        var sourceLimiter = new WeightedByteLimiter(RfsMemorySizing.sourcePermitBytes(
            sourceBytes,
            PRODUCTION_MAX_DOCS_PER_BATCH,
            MAX_REQUEST_BYTES,
            config.concurrency(),
            config.concurrency()
        ));
        var transformLimiter = maxTransformBytes == 0
            ? null
            : new WeightedByteLimiter(Math.multiplyExact(maxTransformBytes, config.concurrency()));
        var transport = new DiscardingRestClient();
        var client = new ProfilingOpenSearchClient(transport);
        client.setSplitLargeDocuments(config.splitEnabled());
        var transformerSupplier = config.sourceMode() == SourceMode.TRANSFORM
            ? (java.util.function.Supplier<IJsonTransformer>) () -> new ExpandingTransformer(config.outputBytes())
            : null;
        var sink = new OpenSearchDocumentSink(
            client,
            transformerSupplier,
            false,
            DocumentExceptionAllowlist.empty(),
            null,
            null,
            maxTransformBytes,
            transformLimiter
        );
        var completedDocuments = new AtomicLong();
        int sampleCapacity = Math.multiplyExact(
            config.warmupSeconds() + config.measureSeconds() + 600,
            1000 / SAMPLING_INTERVAL_MILLIS
        );
        var samples = new HeapSamples(
            sampleCapacity,
            sourceLimiter,
            transformLimiter,
            transport,
            completedDocuments
        );
        Path jfrPath = config.outputDirectory().resolve(config.caseName() + ".jfr");

        long baselineHeap;
        long baselinePostGcHeap;
        long recoveryHeap;
        long measurementDocuments;
        long measurementNanos;
        long measurementRequests;
        int measurementMaxActiveRequests;
        long measurementMaxActiveRequestBytes;
        long liveProbePostFullGcHeap;
        Instant measurementStarted;
        Instant measurementFinished;
        long gcCountBefore;
        long gcMillisBefore;
        long gcCountAfter;
        long gcMillisAfter;
        double trailingWindowOccupancy;
        boolean steadyStateOccupancy;

        try (sink; var recording = recording()) {
            recording.start();
            samples.start();

            samples.phase(Phase.BASELINE);
            baselinePostGcHeap = forceGcAndSettle();
            baselineHeap = baselinePostGcHeap;

            samples.phase(Phase.WARMUP);
            runPhase(
                config,
                sourceFile,
                sourceBytes,
                sourceLimiter,
                sink,
                completedDocuments,
                Math.max(1, config.warmupSeconds()),
                Math.max(2, config.concurrency() * 2)
            );
            requirePermitsReleased(sourceLimiter, transformLimiter);
            transport.awaitIdle();

            completedDocuments.set(0);
            transport.resetMeasurement();
            client.resetMeasurement();
            samples.beginMeasurement();
            gcCountBefore = gcCount();
            gcMillisBefore = gcCollectionMillis();
            measurementStarted = Instant.now();
            long startedNanos = System.nanoTime();
            samples.phase(Phase.MEASUREMENT);
            int measurementChunkSeconds = config.measureSeconds();
            int maximumMeasurementSeconds = config.measureSeconds() < 60
                ? config.measureSeconds() : Math.max(180, config.measureSeconds());
            boolean firstMeasurementChunk = true;
            do {
                runPhase(
                    config,
                    sourceFile,
                    sourceBytes,
                    sourceLimiter,
                    sink,
                    completedDocuments,
                    measurementChunkSeconds,
                    firstMeasurementChunk ? Math.max(8, config.concurrency() * 4) : 0
                );
                transport.awaitIdle();
                samples.endMeasurement();
                trailingWindowOccupancy = samples.trailingWindowOccupancyRatio();
                steadyStateOccupancy =
                    trailingWindowOccupancy >= 0.8 && trailingWindowOccupancy <= 1.2;
                firstMeasurementChunk = false;
                measurementChunkSeconds = 60;
            } while (!steadyStateOccupancy
                && System.nanoTime() - startedNanos
                    < Duration.ofSeconds(maximumMeasurementSeconds).toNanos());
            measurementNanos = System.nanoTime() - startedNanos;
            measurementFinished = Instant.now();
            measurementDocuments = completedDocuments.get();
            measurementRequests = transport.requests();
            measurementMaxActiveRequests = transport.maxActiveRequests();
            measurementMaxActiveRequestBytes = transport.maxActiveRequestBytes();
            gcCountAfter = gcCount();
            gcMillisAfter = gcCollectionMillis();

            requirePermitsReleased(sourceLimiter, transformLimiter);
            samples.phase(Phase.LIVE_PROBE);
            transport.beginLiveHeapProbe(config.concurrency());
            runPhase(
                config,
                sourceFile,
                sourceBytes,
                sourceLimiter,
                sink,
                completedDocuments,
                0,
                config.concurrency()
            );
            transport.awaitIdle();
            liveProbePostFullGcHeap = transport.endLiveHeapProbe();
            requirePermitsReleased(sourceLimiter, transformLimiter);

            samples.phase(Phase.RECOVERY);
            recoveryHeap = forceGcAndSettle();
            recording.stop();
            recording.dump(jfrPath);
        } finally {
            samples.close();
            Files.deleteIfExists(sourceFile);
        }

        var jfr = JfrSummary.read(jfrPath, measurementStarted, measurementFinished);
        long transientPeakHeap = Math.max(samples.measurementPeakHeap(), jfr.maxPreGcHeap());
        long maxPostGcHeap = Math.max(baselinePostGcHeap, jfr.maxPostGcHeap());
        long peakAdmittedBytes = samples.measurementPeakAdmittedBytes();
        double transientFactor = factor(transientPeakHeap, baselineHeap, peakAdmittedBytes);
        double retainedFactor = factor(liveProbePostFullGcHeap, baselinePostGcHeap, peakAdmittedBytes);
        long recoveryTolerance = Math.max(128L * MIB, peakAdmittedBytes / 20);
        boolean stableBaseline = recoveryHeap <= Math.addExact(baselinePostGcHeap, recoveryTolerance);
        double requestsPerDocument = measurementDocuments == 0
            ? 0 : measurementRequests / (double) measurementDocuments;
        long firstHalfAverageHeap = samples.firstHalfAverageHeap();
        long secondHalfAverageHeap = samples.secondHalfAverageHeap();
        double secondToFirstHalfOccupancy = firstHalfAverageHeap == 0
            ? 0 : secondHalfAverageHeap / (double) firstHalfAverageHeap;

        if (measurementDocuments < Math.max(8, config.concurrency() * 4)) {
            throw new AssertionError("measurement completed only " + measurementDocuments + " documents");
        }
        if (peakAdmittedBytes <= 0) {
            throw new AssertionError("no admitted serialized bytes were observed");
        }
        if (sourceLimiter.usedBytes() != 0 || transformLimiter != null && transformLimiter.usedBytes() != 0) {
            throw new AssertionError("serialized-byte permits remained held after recovery");
        }
        if (measurementMaxActiveRequests < config.concurrency()) {
            throw new AssertionError(
                "request concurrency did not compose: expected " + config.concurrency()
                    + ", observed " + measurementMaxActiveRequests
            );
        }
        if (config.pathKind() == PathKind.BELOW_THRESHOLD && requestsPerDocument != 1.0) {
            throw new AssertionError(
                "below-threshold document was split: requestsPerDocument=" + requestsPerDocument
            );
        }
        if (config.pathKind() == PathKind.SPLIT && requestsPerDocument <= 1.0) {
            throw new AssertionError(
                "oversized document did not split: requestsPerDocument=" + requestsPerDocument
            );
        }
        if (!stableBaseline) {
            throw new AssertionError(
                "heap did not return to a stable baseline: baseline=" + baselinePostGcHeap
                    + ", recovery=" + recoveryHeap + ", tolerance=" + recoveryTolerance
            );
        }
        if (!steadyStateOccupancy) {
            throw new AssertionError(
                "heap occupancy did not reach steady state: firstHalfAverage=" + firstHalfAverageHeap
                    + ", secondHalfAverage=" + secondHalfAverageHeap
                    + ", ratio=" + secondToFirstHalfOccupancy
                    + ", trailingWindowRatio=" + trailingWindowOccupancy
            );
        }

        Path samplesPath = config.outputDirectory().resolve(config.caseName() + "-samples.csv");
        samples.write(samplesPath);
        return new Result(
            config.caseName(),
            config.pathKind(),
            config.sourceMode(),
            config.outputMiB(),
            config.splitEnabled(),
            config.concurrency(),
            sourceBytes,
            maxTransformBytes,
            baselineHeap,
            baselinePostGcHeap,
            transientPeakHeap,
            jfr.maxPreGcHeap(),
            maxPostGcHeap,
            liveProbePostFullGcHeap,
            peakAdmittedBytes,
            transientFactor,
            retainedFactor,
            recoveryHeap,
            stableBaseline,
            measurementDocuments,
            measurementNanos,
            measurementDocuments / (measurementNanos / 1_000_000_000.0),
            measurementRequests,
            requestsPerDocument,
            measurementMaxActiveRequests,
            measurementMaxActiveRequestBytes,
            jfr.gcCount(),
            jfr.gcPauseMillis(),
            gcCountAfter - gcCountBefore,
            gcMillisAfter - gcMillisBefore,
            samples.measurementAverageHeap(),
            samples.measurementMinimumHeap(),
            firstHalfAverageHeap,
            secondHalfAverageHeap,
            secondToFirstHalfOccupancy,
            steadyStateOccupancy,
            samplesPath.getFileName().toString(),
            jfrPath.getFileName().toString()
        );
    }

    private static Recording recording() {
        var recording = new Recording();
        recording.setName("rfs-large-document-heap-profile");
        recording.setToDisk(true);
        recording.enable("jdk.GarbageCollection").withThreshold(Duration.ZERO);
        recording.enable("jdk.GCHeapSummary").withThreshold(Duration.ZERO);
        recording.enable("jdk.GCPhasePause").withThreshold(Duration.ZERO);
        return recording;
    }

    private static Path prepareSource(Config config) throws IOException {
        Path source = config.outputDirectory().resolve(config.caseName() + "-source.json");
        byte[] bytes = config.sourceMode() == SourceMode.PASSTHROUGH
            ? JsonObjectSplitterBenchmark.nestedDocument(
                Math.toIntExact(config.outputBytes()),
                VALUE_BYTES,
                CONTAINER_COUNT,
                NESTING_DEPTH
            )
            : "{\"seed\":1}".getBytes(StandardCharsets.UTF_8);
        Files.write(source, bytes);
        return source;
    }

    private static long calibratedTransformBytes(long outputBytes) {
        Map<String, Object> document = nestedDocumentMap(outputBytes);
        var operation = IndexOp.builder()
            .operation(IndexOperationMeta.builder().index(INDEX).id(documentId(0)).build())
            .document(document)
            .build();
        return BulkNdjson.encode(operation, MAPPER).size();
    }

    private static void runPhase(
        Config config,
        Path sourceFile,
        int sourceBytes,
        WeightedByteLimiter sourceLimiter,
        OpenSearchDocumentSink sink,
        AtomicLong completedDocuments,
        int minimumSeconds,
        int minimumDocuments
    ) {
        var partition = new EsShardPartition("heap-profile", INDEX, 0);
        var source = source(partition, sourceFile, config.concurrency());
        var pipeline = new DocumentMigrationPipeline(
            source,
            sink,
            1,
            MAX_REQUEST_BYTES,
            // Keep one document per transform invocation so every matrix cell measures the
            // requested output size. Admission above still uses the production 20k-document
            // request window; otherwise pipeline prefetch can consume the entire two-document
            // test-only window before the persistent preparation lane makes progress.
            1,
            config.concurrency(),
            PipelineMetrics.NONE,
            sourceBytes,
            sourceLimiter
        );
        long started = System.nanoTime();
        long minimumNanos = Duration.ofSeconds(minimumSeconds).toNanos();
        do {
            pipeline.migratePartition(partition, INDEX, 0)
                .doOnNext(cursor -> completedDocuments.addAndGet(cursor.docsInBatch()))
                .then()
                .block(Duration.ofMinutes(10));
        } while (System.nanoTime() - started < minimumNanos
            || completedDocuments.get() < minimumDocuments);
    }

    private static DocumentSource source(
        Partition partition,
        Path sourceFile,
        int documentsPerWave
    ) {
        return new DocumentSource() {
            @Override
            public List<String> listCollections() {
                return List.of(INDEX);
            }

            @Override
            public List<Partition> listPartitions(String collectionName) {
                return List.of(partition);
            }

            @Override
            public CollectionMetadata readCollectionMetadata(String collectionName) {
                return new CollectionMetadata(collectionName, 1, Map.of());
            }

            @Override
            public Flux<Document> readDocuments(Partition ignored, long startingDocOffset) {
                throw new AssertionError("memory-aware source path was not used");
            }

            @Override
            public Flux<Document> readDocuments(
                Partition ignored,
                long startingDocOffset,
                SourceDocumentMemoryTracker memoryTracker
            ) {
                return Flux.range(Math.toIntExact(startingDocOffset), documentsPerWave)
                    .flatMapSequential(
                        index -> memoryTracker.materialize(
                            Mono.fromCallable(() -> readDocument(sourceFile, index))
                                .subscribeOn(Schedulers.boundedElastic()),
                            Document::sourceLength,
                            Document::id
                        ),
                        memoryTracker.readParallelism(),
                        1
                    );
            }
        };
    }

    private static Document readDocument(Path sourceFile, int index) {
        try {
            return new Document(
                documentId(index),
                Files.readAllBytes(sourceFile),
                Document.Operation.UPSERT,
                Map.of(),
                Map.of()
            );
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String documentId(long index) {
        return "doc-" + String.format(Locale.ROOT, "%020d", index);
    }

    private static Map<String, Object> nestedDocumentMap(long targetBytes) {
        String leafValue = "x".repeat(VALUE_BYTES);
        int leafCount = Math.max(1, Math.toIntExact(targetBytes / (VALUE_BYTES + 25)));
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, Object> groups = new LinkedHashMap<>();
        root.put("benchmark_payload", payload);
        payload.put("target_bytes", targetBytes);
        payload.put("groups", groups);
        int leaf = 0;
        for (int group = 0; group < CONTAINER_COUNT && leaf < leafCount; ++group) {
            Map<String, Object> groupRoot = new LinkedHashMap<>();
            groups.put(padded("group_", group, 4), groupRoot);
            Map<String, Object> cursor = groupRoot;
            for (int depth = 0; depth < NESTING_DEPTH; ++depth) {
                Map<String, Object> child = new LinkedHashMap<>();
                cursor.put(padded("level_", depth, 2), child);
                cursor = child;
            }
            int groupEnd = (int) Math.ceil((group + 1.0) * leafCount / CONTAINER_COUNT);
            while (leaf < groupEnd) {
                cursor.put(padded("key_", leaf, 7), leafValue);
                ++leaf;
            }
        }
        return root;
    }

    private static String padded(String prefix, int value, int width) {
        String unpadded = Integer.toString(value);
        return prefix + "0".repeat(Math.max(0, width - unpadded.length())) + unpadded;
    }

    private static double factor(long peak, long baseline, long admitted) {
        return admitted == 0 ? 0 : Math.max(0, peak - baseline) / (double) admitted;
    }

    private static void requirePermitsReleased(
        WeightedByteLimiter sourceLimiter,
        WeightedByteLimiter transformLimiter
    ) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while ((sourceLimiter.usedBytes() != 0
            || transformLimiter != null && transformLimiter.usedBytes() != 0)
            && System.nanoTime() < deadline) {
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
        if (sourceLimiter.usedBytes() != 0
            || transformLimiter != null && transformLimiter.usedBytes() != 0) {
            throw new AssertionError(
                "permits did not release: source=" + sourceLimiter.usedBytes()
                    + ", transform=" + (transformLimiter == null ? 0 : transformLimiter.usedBytes())
            );
        }
    }

    private static long forceGcAndSettle() {
        long minimum = heapUsed();
        for (int attempt = 0; attempt < 3; ++attempt) {
            long before = gcCount();
            System.gc();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (gcCount() <= before && System.nanoTime() < deadline) {
                LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
            }
            for (int sample = 0; sample < 10; ++sample) {
                LockSupport.parkNanos(Duration.ofMillis(25).toNanos());
                minimum = Math.min(minimum, heapUsed());
            }
        }
        return minimum;
    }

    private static long heapUsed() {
        return MEMORY_MX_BEAN.getHeapMemoryUsage().getUsed();
    }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
            .mapToLong(GarbageCollectorMXBean::getCollectionCount)
            .filter(value -> value >= 0)
            .sum();
    }

    private static long gcCollectionMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
            .mapToLong(GarbageCollectorMXBean::getCollectionTime)
            .filter(value -> value >= 0)
            .sum();
    }

    private enum Phase {
        SETUP,
        WARMUP,
        BASELINE,
        MEASUREMENT,
        LIVE_PROBE,
        RECOVERY
    }

    enum PathKind {
        NORMAL,
        BELOW_THRESHOLD,
        SPLIT
    }

    enum SourceMode {
        PASSTHROUGH,
        TRANSFORM
    }

    private record Config(
        Path outputDirectory,
        PathKind pathKind,
        SourceMode sourceMode,
        int outputMiB,
        boolean splitEnabled,
        int concurrency,
        int warmupSeconds,
        int measureSeconds
    ) {
        private static Config parse(String[] args) {
            if (args.length != 8) {
                throw new IllegalArgumentException(
                    "expected outputDirectory pathKind sourceMode outputMiB splitEnabled concurrency "
                        + "warmupSeconds measureSeconds"
                );
            }
            return new Config(
                Path.of(args[0]),
                PathKind.valueOf(args[1]),
                SourceMode.valueOf(args[2]),
                Integer.parseInt(args[3]),
                Boolean.parseBoolean(args[4]),
                Integer.parseInt(args[5]),
                Integer.parseInt(args[6]),
                Integer.parseInt(args[7])
            );
        }

        private long outputBytes() {
            return Math.multiplyExact((long) outputMiB, MIB);
        }

        private String caseName() {
            return pathKind.name().toLowerCase(Locale.ROOT)
                + "-" + sourceMode.name().toLowerCase(Locale.ROOT)
                + "-" + outputMiB + "mib-c" + concurrency;
        }
    }

    @SuppressWarnings("unchecked")
    private static final class ExpandingTransformer implements IJsonTransformer {
        private final long outputBytes;

        private ExpandingTransformer(long outputBytes) {
            this.outputBytes = outputBytes;
        }

        @Override
        public Object transformJson(Object input) {
            var operations = (List<Map<String, Object>>) input;
            for (var operation : operations) {
                operation.put("document", nestedDocumentMap(outputBytes));
            }
            return operations;
        }
    }

    private static final class ProfilingOpenSearchClient extends OpenSearchClient_OS_2_11 {
        private final AtomicLong maxPreparedTransformBytes = new AtomicLong();

        private ProfilingOpenSearchClient(RestClient transport) {
            super(
                transport,
                new FailedRequestsLogger(),
                Version.fromString("OS 2.11"),
                CompressionMode.UNCOMPRESSED,
                1,
                MAX_REQUEST_BYTES
            );
        }

        @Override
        public PreparedTransformedDocument prepareTransformedDocument(
            String indexName,
            org.opensearch.migrations.bulkload.common.bulk.BulkOperationSpec operation,
            org.opensearch.migrations.bulkload.tracing.IRfsContexts.IRequestContext context,
            boolean allowServerGeneratedIds
        ) {
            var prepared = super.prepareTransformedDocument(
                indexName, operation, context, allowServerGeneratedIds);
            if (prepared != null) {
                maxPreparedTransformBytes.accumulateAndGet(prepared.prepared().size(), Math::max);
            }
            return prepared;
        }

        private void resetMeasurement() {
            maxPreparedTransformBytes.set(0);
        }
    }

    private static final class DiscardingRestClient extends RestClient {
        private static final String INDEX_DEFINITION =
            "{\"heap-profile\":{\"mappings\":{},\"settings\":{"
                + "\"index.default_pipeline\":\"_none\",\"index.final_pipeline\":\"_none\"}}}";

        private final AtomicLong requests = new AtomicLong();
        private final AtomicLong activeRequests = new AtomicLong();
        private final AtomicLong activeRequestBytes = new AtomicLong();
        private final AtomicInteger maxActiveRequests = new AtomicInteger();
        private final AtomicLong maxActiveRequestBytes = new AtomicLong();
        private final AtomicBoolean liveProbeStarted = new AtomicBoolean();
        private final AtomicLong liveProbePostFullGcHeap = new AtomicLong();
        private volatile boolean liveProbeEnabled;
        private volatile int liveProbeConcurrency;

        private DiscardingRestClient() {
            super(ConnectionContextTestParams.builder()
                .host("http://localhost")
                .insecure(true)
                .build()
                .toConnectionContext());
        }

        @Override
        public Mono<HttpResponse> getAsync(
            String path,
            org.opensearch.migrations.bulkload.tracing.IRfsContexts.IRequestContext context
        ) {
            return Mono.just(new HttpResponse(200, "OK", Map.of(), INDEX_DEFINITION));
        }

        @Override
        public Mono<HttpResponse> postAsyncBytes(
            String path,
            byte[] body,
            Map<String, List<String>> additionalHeaders,
            org.opensearch.migrations.bulkload.tracing.IRfsContexts.IRequestContext context
        ) {
            return successfulRequest(body);
        }

        @Override
        public Mono<HttpResponse> postAsyncBytes(
            String path,
            byte[] body,
            Map<String, List<String>> additionalHeaders,
            org.opensearch.migrations.bulkload.tracing.IRfsContexts.IRequestContext context,
            Duration responseTimeout
        ) {
            return successfulRequest(body);
        }

        private Mono<HttpResponse> successfulRequest(byte[] body) {
            String action = startsWith(body, "{\"create\"") ? "create"
                : startsWith(body, "{\"update\"") ? "update" : "index";
            long sequence = requests.incrementAndGet();
            int active = Math.toIntExact(activeRequests.incrementAndGet());
            long bytes = activeRequestBytes.addAndGet(body.length);
            maxActiveRequests.accumulateAndGet(active, Math::max);
            maxActiveRequestBytes.accumulateAndGet(bytes, Math::max);
            boolean probing = liveProbeEnabled;
            if (probing
                && active >= liveProbeConcurrency
                && liveProbeStarted.compareAndSet(false, true)) {
                liveProbePostFullGcHeap.set(forceSingleGcAndReadHeap());
            }
            String response = "{\"errors\":false,\"items\":[{\"" + action + "\":{"
                + "\"_index\":\"" + INDEX + "\",\"_id\":\"discarded\",\"status\":201,"
                + "\"_seq_no\":" + sequence + ",\"_primary_term\":1}}]}";
            int delayMillis = probing && !liveProbeStarted.get()
                ? LIVE_PROBE_TARGET_DELAY_MILLIS : TARGET_DELAY_MILLIS;
            return Mono.delay(Duration.ofMillis(delayMillis))
                .map(ignored -> new HttpResponse(200, "OK", Map.of(), response))
                .doFinally(ignored -> {
                    activeRequests.decrementAndGet();
                    activeRequestBytes.addAndGet(-body.length);
                });
        }

        private static boolean startsWith(byte[] body, String prefix) {
            byte[] expected = prefix.getBytes(StandardCharsets.UTF_8);
            if (body.length < expected.length) {
                return false;
            }
            for (int i = 0; i < expected.length; ++i) {
                if (body[i] != expected[i]) {
                    return false;
                }
            }
            return true;
        }

        private void resetMeasurement() {
            if (activeRequests.get() != 0 || activeRequestBytes.get() != 0) {
                throw new AssertionError(
                    "cannot reset active transport: requests=" + activeRequests.get()
                        + ", bytes=" + activeRequestBytes.get()
                );
            }
            requests.set(0);
            maxActiveRequests.set(0);
            maxActiveRequestBytes.set(0);
        }

        private void beginLiveHeapProbe(int concurrency) {
            if (activeRequests.get() != 0 || activeRequestBytes.get() != 0) {
                throw new AssertionError("transport must be idle before the live-heap probe");
            }
            liveProbeConcurrency = concurrency;
            liveProbePostFullGcHeap.set(0);
            liveProbeStarted.set(false);
            liveProbeEnabled = true;
        }

        private long endLiveHeapProbe() {
            liveProbeEnabled = false;
            long heap = liveProbePostFullGcHeap.get();
            if (!liveProbeStarted.get() || heap <= 0) {
                throw new AssertionError("live-heap probe did not observe the configured concurrency");
            }
            return heap;
        }

        private void awaitIdle() {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while ((activeRequests.get() != 0 || activeRequestBytes.get() != 0)
                && System.nanoTime() < deadline) {
                LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
            }
            if (activeRequests.get() != 0 || activeRequestBytes.get() != 0) {
                throw new AssertionError(
                    "transport did not become idle: requests=" + activeRequests.get()
                        + ", bytes=" + activeRequestBytes.get()
                );
            }
        }

        private long requests() {
            return requests.get();
        }

        private int maxActiveRequests() {
            return maxActiveRequests.get();
        }

        private long maxActiveRequestBytes() {
            return maxActiveRequestBytes.get();
        }
    }

    private static long forceSingleGcAndReadHeap() {
        long before = gcCount();
        System.gc();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (gcCount() <= before && System.nanoTime() < deadline) {
            LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
        }
        if (gcCount() <= before) {
            throw new AssertionError("the in-flight full-GC live-heap probe did not collect");
        }
        long minimum = heapUsed();
        for (int sample = 0; sample < 10; ++sample) {
            LockSupport.parkNanos(Duration.ofMillis(5).toNanos());
            minimum = Math.min(minimum, heapUsed());
        }
        return minimum;
    }

    private static final class HeapSamples implements AutoCloseable {
        private final long[] elapsedNanos;
        private final long[] heapBytes;
        private final long[] admittedBytes;
        private final long[] activeRequestBytes;
        private final long[] completedDocuments;
        private final byte[] phases;
        private final WeightedByteLimiter sourceLimiter;
        private final WeightedByteLimiter transformLimiter;
        private final DiscardingRestClient transport;
        private final AtomicLong completed;
        private final AtomicInteger size = new AtomicInteger();
        private final Thread thread;
        private volatile boolean running;
        private volatile Phase phase = Phase.SETUP;
        private volatile long startedNanos;
        private volatile int measurementStart;
        private volatile int measurementEnd;

        private HeapSamples(
            int capacity,
            WeightedByteLimiter sourceLimiter,
            WeightedByteLimiter transformLimiter,
            DiscardingRestClient transport,
            AtomicLong completed
        ) {
            elapsedNanos = new long[capacity];
            heapBytes = new long[capacity];
            admittedBytes = new long[capacity];
            activeRequestBytes = new long[capacity];
            completedDocuments = new long[capacity];
            phases = new byte[capacity];
            this.sourceLimiter = sourceLimiter;
            this.transformLimiter = transformLimiter;
            this.transport = transport;
            this.completed = completed;
            thread = new Thread(this::sample, "rfs-heap-profile-sampler");
            thread.setDaemon(true);
        }

        private void start() {
            startedNanos = System.nanoTime();
            running = true;
            thread.start();
        }

        private void phase(Phase value) {
            phase = value;
        }

        private void beginMeasurement() {
            measurementStart = size.get();
        }

        private void endMeasurement() {
            measurementEnd = size.get();
        }

        private void sample() {
            while (running) {
                int index = size.getAndIncrement();
                if (index >= heapBytes.length) {
                    running = false;
                    size.set(heapBytes.length);
                    return;
                }
                elapsedNanos[index] = System.nanoTime() - startedNanos;
                heapBytes[index] = heapUsed();
                admittedBytes[index] = sourceLimiter.usedBytes()
                    + (transformLimiter == null ? 0 : transformLimiter.usedBytes());
                activeRequestBytes[index] = transport.activeRequestBytes.get();
                completedDocuments[index] = completed.get();
                phases[index] = (byte) phase.ordinal();
                LockSupport.parkNanos(Duration.ofMillis(SAMPLING_INTERVAL_MILLIS).toNanos());
            }
        }

        private long measurementPeakHeap() {
            return maximum(heapBytes);
        }

        private long measurementMinimumHeap() {
            return minimum(heapBytes);
        }

        private long measurementPeakAdmittedBytes() {
            return maximum(admittedBytes);
        }

        private long measurementAverageHeap() {
            return average(heapBytes, measurementStart, measurementEnd);
        }

        private long firstHalfAverageHeap() {
            int middle = measurementStart + (measurementEnd - measurementStart) / 2;
            return average(heapBytes, measurementStart, middle);
        }

        private long secondHalfAverageHeap() {
            int middle = measurementStart + (measurementEnd - measurementStart) / 2;
            return average(heapBytes, middle, measurementEnd);
        }

        private double trailingWindowOccupancyRatio() {
            int quarter = (measurementEnd - measurementStart) / 4;
            if (quarter == 0) {
                return 0;
            }
            long earlier = average(
                heapBytes,
                measurementEnd - 2 * quarter,
                measurementEnd - quarter
            );
            long later = average(heapBytes, measurementEnd - quarter, measurementEnd);
            return earlier == 0 ? 0 : later / (double) earlier;
        }

        private long maximum(long[] values) {
            long maximum = 0;
            for (int i = measurementStart; i < measurementEnd; ++i) {
                maximum = Math.max(maximum, values[i]);
            }
            return maximum;
        }

        private long minimum(long[] values) {
            long minimum = Long.MAX_VALUE;
            for (int i = measurementStart; i < measurementEnd; ++i) {
                minimum = Math.min(minimum, values[i]);
            }
            return minimum == Long.MAX_VALUE ? 0 : minimum;
        }

        private static long average(long[] values, int start, int end) {
            if (end <= start) {
                return 0;
            }
            double total = 0;
            for (int i = start; i < end; ++i) {
                total += values[i];
            }
            return Math.round(total / (end - start));
        }

        private void write(Path output) throws IOException {
            try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
                writer.write(
                    "elapsed_nanos,phase,heap_bytes,admitted_serialized_bytes,"
                        + "active_request_bytes,completed_documents\n"
                );
                int sampleCount = Math.min(size.get(), heapBytes.length);
                for (int i = 0; i < sampleCount; ++i) {
                    writer.write(Long.toString(elapsedNanos[i]));
                    writer.write(',');
                    writer.write(Phase.values()[phases[i]].name());
                    writer.write(',');
                    writer.write(Long.toString(heapBytes[i]));
                    writer.write(',');
                    writer.write(Long.toString(admittedBytes[i]));
                    writer.write(',');
                    writer.write(Long.toString(activeRequestBytes[i]));
                    writer.write(',');
                    writer.write(Long.toString(completedDocuments[i]));
                    writer.newLine();
                }
            }
        }

        @Override
        public void close() {
            running = false;
            try {
                thread.join(Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private record JfrSummary(long maxPreGcHeap, long maxPostGcHeap, long gcCount, double gcPauseMillis) {
        private static JfrSummary read(Path path, Instant started, Instant finished) throws IOException {
            long maxPreGc = 0;
            long maxPostGc = 0;
            long pauseNanos = 0;
            var collectionIds = new HashSet<Long>();
            try (var events = new RecordingFile(path)) {
                while (events.hasMoreEvents()) {
                    RecordedEvent event = events.readEvent();
                    if (event.getStartTime().isBefore(started) || event.getStartTime().isAfter(finished)) {
                        continue;
                    }
                    if ("jdk.GarbageCollection".equals(event.getEventType().getName())) {
                        collectionIds.add(event.getLong("gcId"));
                    } else if ("jdk.GCPhasePause".equals(event.getEventType().getName())) {
                        pauseNanos = Math.addExact(pauseNanos, event.getDuration().toNanos());
                    } else if ("jdk.GCHeapSummary".equals(event.getEventType().getName())) {
                        long used = event.getLong("heapUsed");
                        Object whenValue = event.getValue("when");
                        String when = String.valueOf(whenValue).toLowerCase(Locale.ROOT);
                        if (when.contains("before")) {
                            maxPreGc = Math.max(maxPreGc, used);
                        } else if (when.contains("after")) {
                            maxPostGc = Math.max(maxPostGc, used);
                        }
                    }
                }
            }
            return new JfrSummary(maxPreGc, maxPostGc, collectionIds.size(), pauseNanos / 1_000_000.0);
        }
    }

    private record Result(
        String caseName,
        PathKind pathKind,
        SourceMode sourceMode,
        int outputMiB,
        boolean splitEnabled,
        int concurrency,
        long sourceBytes,
        long maxTransformBytes,
        long baselineHeap,
        long baselinePostGcHeap,
        long peakHeap,
        long maxPreGcHeap,
        long maxPostGcOccupancy,
        long liveProbePostFullGcHeap,
        long peakAdmittedSerializedBytes,
        double transientFactor,
        double retainedFactor,
        long recoveryHeap,
        boolean stableBaseline,
        long documents,
        long elapsedNanos,
        double documentsPerSecond,
        long requests,
        double requestsPerDocument,
        int maxActiveRequests,
        long maxActiveRequestBytes,
        long jfrGcCount,
        double jfrGcPauseMillis,
        long mxGcCount,
        long mxGcMillis,
        long averageHeap,
        long minimumHeap,
        long firstHalfAverageHeap,
        long secondHalfAverageHeap,
        double secondToFirstHalfOccupancy,
        boolean steadyStateOccupancy,
        String samplesFile,
        String jfrFile
    ) {
        private static final String HEADER =
            "case,path,source_mode,output_mib,split_enabled,concurrency,source_bytes,max_transform_bytes,"
                + "baseline_heap_bytes,baseline_post_gc_heap_bytes,peak_heap_bytes,max_pre_gc_heap_bytes,"
                + "max_post_gc_occupancy_bytes,live_probe_post_full_gc_heap_bytes,"
                + "peak_admitted_serialized_bytes,transient_factor,retained_factor,recovery_heap_bytes,"
                + "stable_baseline,documents,elapsed_nanos,documents_per_second,requests,"
                + "requests_per_document,max_active_requests,max_active_request_bytes,jfr_gc_count,"
                + "jfr_gc_pause_millis,mx_gc_count,mx_gc_millis,average_heap_bytes,minimum_heap_bytes,"
                + "first_half_average_heap_bytes,second_half_average_heap_bytes,"
                + "second_to_first_half_occupancy,steady_state_occupancy,samples_file,jfr_file";

        private String csv() {
            return String.join(",",
                caseName,
                pathKind.name(),
                sourceMode.name(),
                Integer.toString(outputMiB),
                Boolean.toString(splitEnabled),
                Integer.toString(concurrency),
                Long.toString(sourceBytes),
                Long.toString(maxTransformBytes),
                Long.toString(baselineHeap),
                Long.toString(baselinePostGcHeap),
                Long.toString(peakHeap),
                Long.toString(maxPreGcHeap),
                Long.toString(maxPostGcOccupancy),
                Long.toString(liveProbePostFullGcHeap),
                Long.toString(peakAdmittedSerializedBytes),
                format(transientFactor),
                format(retainedFactor),
                Long.toString(recoveryHeap),
                Boolean.toString(stableBaseline),
                Long.toString(documents),
                Long.toString(elapsedNanos),
                format(documentsPerSecond),
                Long.toString(requests),
                format(requestsPerDocument),
                Integer.toString(maxActiveRequests),
                Long.toString(maxActiveRequestBytes),
                Long.toString(jfrGcCount),
                format(jfrGcPauseMillis),
                Long.toString(mxGcCount),
                Long.toString(mxGcMillis),
                Long.toString(averageHeap),
                Long.toString(minimumHeap),
                Long.toString(firstHalfAverageHeap),
                Long.toString(secondHalfAverageHeap),
                format(secondToFirstHalfOccupancy),
                Boolean.toString(steadyStateOccupancy),
                samplesFile,
                jfrFile
            );
        }

        private void write(Path path) throws IOException {
            Files.writeString(path, HEADER + System.lineSeparator() + csv() + System.lineSeparator());
        }

        private String toKeyValues() {
            return "case=" + caseName
                + " path=" + pathKind
                + " sourceMode=" + sourceMode
                + " outputMiB=" + outputMiB
                + " concurrency=" + concurrency
                + " documents=" + documents
                + " peakAdmittedBytes=" + peakAdmittedSerializedBytes
                + " transientFactor=" + format(transientFactor)
                + " retainedFactor=" + format(retainedFactor)
                + " recoveryStable=" + stableBaseline
                + " requestsPerDocument=" + format(requestsPerDocument)
                + " gcCount=" + jfrGcCount
                + " gcPauseMillis=" + format(jfrGcPauseMillis)
                + " steadyStateOccupancy=" + steadyStateOccupancy;
        }

        private static String format(double value) {
            return String.format(Locale.ROOT, "%.6f", value);
        }
    }
}
