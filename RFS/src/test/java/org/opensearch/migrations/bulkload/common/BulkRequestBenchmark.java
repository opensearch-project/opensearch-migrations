package org.opensearch.migrations.bulkload.common;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.bulk.BulkNdjson;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationConverter;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationSpec;
import org.opensearch.migrations.bulkload.common.http.CompressionMode;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.bulkload.version_os_2_11.OpenSearchClient_OS_2_11;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import reactor.core.publisher.Mono;

/**
 * Measures serialization and the complete client path up to the HTTP transport.
 * Fixtures fit in one default request on both revisions, so comparisons do equal work.
 * Copy this file unchanged to the baseline checkout for an A/B comparison.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class BulkRequestBenchmark {
    private static final String INDEX_NAME = "benchmark";
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultMapper();

    @Param({"1x256", "128x256", "128x4096", "128x65536"})
    public String batch;

    @Param({"raw", "native", "mapped"})
    public String representation;

    private List<Document> documents;
    private List<BulkOperationSpec> operations;
    private OpenSearchClient client;
    private RecordingTransport transport;

    @Setup
    public void setup() throws IOException {
        var dimensions = batch.split("x");
        int count = Integer.parseInt(dimensions[0]);
        int sourceBytes = Integer.parseInt(dimensions[1]);
        documents = new ArrayList<>(count);
        operations = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            var source = MAPPER.writeValueAsBytes(Map.of("value", "x".repeat(sourceBytes - 12)));
            var doc = new Document("doc-" + i, source, Document.Operation.UPSERT,
                Map.of(Document.HINT_ROUTING, "route-" + i), Map.of());
            documents.add(doc);
            operations.add("mapped".equals(representation)
                ? BulkOperationConverter.fromDocument(doc, INDEX_NAME)
                : BulkOperationConverter.fromRawDocument(doc, INDEX_NAME));
        }
        var args = new ConnectionContext.TargetArgs();
        args.host = "http://localhost:9200";
        transport = new RecordingTransport(args.toConnectionContext());
        client = new OpenSearchClient_OS_2_11(transport, new FailedRequestsLogger(),
            Version.fromString("OS 2.11"), CompressionMode.UNCOMPRESSED);
        var expected = serialize().bytes();
        var response = request();
        if (response.requests.total() != 1 || !Arrays.equals(expected, transport.body)
            || expected.length > 10L * 1024 * 1024) {
            throw new IllegalStateException("Benchmark must send one complete, identical request");
        }
    }

    @Benchmark
    public BulkNdjson.SerializedBulk serialize() {
        return "raw".equals(representation)
            ? BulkNdjson.serializeRaw(documents, INDEX_NAME, false, MAPPER)
            : BulkNdjson.serialize(operations, MAPPER);
    }

    @Benchmark
    public void send(Blackhole blackhole) {
        blackhole.consume(request());
        blackhole.consume(transport.body);
    }

    private OpenSearchClient.BulkResponse request() {
        return ("raw".equals(representation)
            ? client.sendBulkRequestRaw(INDEX_NAME, documents, null, false, DocumentExceptionAllowlist.empty())
            : client.sendBulkRequest(INDEX_NAME, operations, null)).block();
    }

    private static class RecordingTransport extends RestClient {
        private static final Mono<HttpResponse> OK =
            Mono.just(new HttpResponse(200, "OK", Map.of(), "{\"errors\":false,\"items\":[]}"));
        private byte[] body;

        RecordingTransport(ConnectionContext connection) {
            super(connection);
        }

        @Override
        public Mono<HttpResponse> postAsyncBytes(
            String path, byte[] requestBody, Map<String, List<String>> headers, IRfsContexts.IRequestContext context
        ) {
            body = requestBody;
            return OK;
        }
    }
}
