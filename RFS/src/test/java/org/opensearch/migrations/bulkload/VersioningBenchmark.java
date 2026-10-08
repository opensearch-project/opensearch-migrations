package org.opensearch.migrations.bulkload;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.DocumentChangeType;
import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.LuceneDocumentChange;
import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.bulk.BulkNdjson;
import org.opensearch.migrations.bulkload.common.http.CompressionMode;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.bulkload.lucene.LuceneDirectoryReader;
import org.opensearch.migrations.bulkload.lucene.LuceneIndexReader;
import org.opensearch.migrations.bulkload.lucene.LuceneLeafReader;
import org.opensearch.migrations.bulkload.lucene.LuceneReader;
import org.opensearch.migrations.bulkload.lucene.version_10.IndexReader10;
import org.opensearch.migrations.bulkload.lucene.version_9.IndexReader9;
import org.opensearch.migrations.bulkload.pipeline.adapter.LuceneAdapter;
import org.opensearch.migrations.bulkload.pipeline.adapter.OpenSearchDocumentSink;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.bulkload.version_os_2_11.OpenSearchClient_OS_2_11;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.TransformationLoader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import reactor.core.publisher.Mono;

/**
 * Opt-in benchmarks over real OpenSearch shard files. See RFS/benchmarks/README.md.
 * Uses APIs shared with main so the identical harness can measure both revisions.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 3, jvmArgsAppend = {"-Xms1g", "-Xmx1g", "-XX:ActiveProcessorCount=4"})
public class VersioningBenchmark {
    private static final int BATCH_SIZE = 256;
    private static final String INDEX = "benchmark";
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultMapper();
    private static final LuceneAdapter ADAPTER = new LuceneAdapter();

    @State(Scope.Thread)
    public static class Input {
        @Param({"build/benchmarks/small-varied"})
        public String fixture;

        private Path indexPath;
        private String segments;
        private LuceneIndexReader factory;
        private LuceneDirectoryReader directory;
        private LuceneLeafReader leaf;
        private String sourceVersion;
        private int nextDoc;
        private int documentCount;

        @Setup
        public void open() throws Exception {
            var root = Path.of(fixture);
            var manifest = MAPPER.readTree(root.resolve("manifest.json").toFile());
            sourceVersion = manifest.path("version").asText();
            indexPath = root.resolve("index");
            try (var files = Files.list(indexPath)) {
                segments = files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("segments_")).findFirst().orElseThrow();
            }
            factory = manifest.path("lucene").asText().startsWith("10.")
                ? new IndexReader10(indexPath, true, "_soft_deletes")
                : new IndexReader9(indexPath, true, "_soft_deletes");
            directory = factory.getReader(segments);
            if (directory.leaves().size() != 1) {
                throw new IllegalArgumentException("Force-merge the fixture to one segment first");
            }
            leaf = directory.leaves().get(0).reader();
            documentCount = manifest.path("documents").asInt();
            if (leaf.maxDoc() != documentCount || documentCount % BATCH_SIZE != 0) {
                throw new IllegalArgumentException("Unexpected document count");
            }
            // Untimed correctness check: compare native output to the real Lucene doc value.
            boolean expected = Boolean.parseBoolean(System.getProperty("benchmark.expectVersions", "true"));
            for (int id = 0; id < 4; id++) {
                var document = ADAPTER.fromLucene(read());
                var bytes = BulkNdjson.toRawNdjsonBytes(List.of(document), INDEX, false, MAPPER);
                try (var parser = MAPPER.createParser(bytes)) {
                    JsonNode action = MAPPER.<JsonNode>readTree(parser).path("index");
                    if (expected) {
                        long actualVersion = (Long) leaf.getNumericValue(id, "_version");
                        if (action.path("version").longValue() != actualVersion
                            || !"external".equals(action.path("version_type").asText())) {
                            throw new IllegalStateException("Native output did not preserve the source version");
                        }
                    } else if (action.has("version") || action.has("version_type")) {
                        throw new IllegalStateException("Baseline unexpectedly emitted version metadata");
                    }
                }
            }
            nextDoc = 0;
        }

        private LuceneDocumentChange read() {
            int id = nextDoc++;
            if (nextDoc == documentCount) {
                nextDoc = 0;
            }
            return LuceneReader.getDocument(leaf, id, true, 0, () -> "benchmark", indexPath,
                DocumentChangeType.INDEX, null, null, false);
        }

        @TearDown
        public void close() throws Exception {
            directory.close();
        }
    }

    @State(Scope.Thread)
    public static class Output {
        @Param({"native", "identity", "external_gte", "java_gte"})
        public String mode;

        private IJsonTransformer transformer;
        private OpenSearchDocumentSink sink;
        private CapturingRestClient transport;

        @Setup
        public void open(Input input) throws Exception {
            String config = switch (mode) {
                case "native", "java_gte" -> null;
                case "identity" -> """
                        [{"JsonJSTransformerProvider": {
                          "initializationScript": "context => documents => documents"
                        }}]
                        """;
                case "external_gte" -> """
                        [{"JsonJSTransformerProvider": {
                          "initializationResourcePath": "js/externalVersioning.js",
                          "bindingsObject": {"versionType": "external_gte"}
                        }}]
                        """;
                default -> throw new IllegalArgumentException("Unknown benchmark mode: " + mode);
            };
            if (config != null) {
                transformer = new TransformationLoader().getTransformerFactoryLoader(config);
            } else if ("java_gte".equals(mode)) {
                transformer = VersioningBenchmark::externalGteInJava;
            }
            transport = new CapturingRestClient();
            var client = new OpenSearchClient_OS_2_11(transport, new FailedRequestsLogger(),
                Version.fromString("OS " + input.sourceVersion), CompressionMode.UNCOMPRESSED);
            sink = new OpenSearchDocumentSink(client, transformer == null ? null : () -> transformer,
                false, DocumentExceptionAllowlist.empty(), null);
            new VersioningBenchmark().pipeline(input, this);
            try (var parser = MAPPER.createParser(transport.lastBody)) {
                JsonNode action = MAPPER.<JsonNode>readTree(parser).path("index");
                if (mode.endsWith("gte") && !"external_gte".equals(action.path("version_type").asText())) {
                    throw new IllegalStateException("The configured version policy was not applied");
                }
            }
        }

        @TearDown
        public void close() throws Exception {
            if (transformer != null) {
                transformer.close();
            }
        }
    }

    /**
     * Direct Java comparator for the snapshot-policy case: no scripting engine or Jolt.
     * This is benchmark code, not a registered provider or an application-field modifier.
     */
    @SuppressWarnings("unchecked")
    private static Object externalGteInJava(Object input) {
        for (var item : (List<Map<String, Object>>) input) {
            if (!"rfs-opensearch-bulk-v1".equals(item.get("schema"))
                || !"index".equals(item.get("operation_type"))) {
                continue;
            }
            var operation = (Map<String, Object>) item.get("operation");
            if (!(operation.get("_id") instanceof String id) || id.isEmpty()
                || "create".equals(operation.get("op_type"))) {
                throw new IllegalArgumentException("External versioning requires an index operation with a source id");
            }
            Object version = operation.get("version");
            if (version == null && item.get("source_metadata") instanceof Map<?, ?> metadata) {
                version = metadata.get("_version");
            }
            String text = String.valueOf(version);
            long numeric = Long.parseLong(text);
            if (numeric < 0 || !Long.toString(numeric).equals(text)) {
                throw new IllegalArgumentException("Expected a non-negative canonical long version");
            }
            operation.put("version", version);
            operation.put("version_type", "external_gte");
            operation.remove("if_seq_no");
            operation.remove("if_primary_term");
        }
        return input;
    }

    /** Stored source + id + (on the PR) the version doc value; sequential document access. */
    @Benchmark
    public LuceneDocumentChange readDocument(Input input) {
        return input.read();
    }

    /** Sequential read, adaptation and native bulk serialization, normalized per document. */
    @Benchmark
    @OperationsPerInvocation(BATCH_SIZE)
    public byte[] nativeBatch(Input input) {
        var documents = new ArrayList<Document>(BATCH_SIZE);
        for (int i = 0; i < BATCH_SIZE; i++) {
            documents.add(ADAPTER.fromLucene(input.read()));
        }
        return BulkNdjson.toRawNdjsonBytes(documents, INDEX, false, MAPPER);
    }

    /**
     * Production concurrent reader, sink, transformer and serializer, through the HTTP boundary.
     * One operation is the WHOLE fixture. Excludes network, compression and target indexing.
     */
    @Benchmark
    public long pipeline(Input input, Output output) {
        output.transport.bytes = 0;
        long documents = LuceneReader.streamDocumentChanges(input.factory, input.segments)
            .map(ADAPTER::fromLucene)
            .buffer(BATCH_SIZE)
            .concatMap(batch -> output.sink.writeBatch(INDEX, batch))
            .map(result -> result.docsInBatch())
            .reduce(0L, Long::sum)
            .block();
        if (documents != input.documentCount) {
            throw new IllegalStateException("The pipeline dropped documents");
        }
        return output.transport.bytes;
    }

    /** Keep the production serialization and success path, replacing only the HTTP exchange. */
    private static class CapturingRestClient extends RestClient {
        private long bytes;
        private byte[] lastBody;

        CapturingRestClient() {
            super(ConnectionContextTestParams.builder().host("http://localhost:1").build().toConnectionContext());
        }

        @Override
        public Mono<HttpResponse> postAsyncBytes(String path, byte[] body,
                                                Map<String, List<String>> headers,
                                                IRfsContexts.IRequestContext context) {
            bytes += body.length;
            lastBody = body;
            return Mono.just(new HttpResponse(200, "OK", Map.of(), "{\"errors\":false,\"items\":[]}"));
        }
    }
}
