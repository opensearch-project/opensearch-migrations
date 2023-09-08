package org.opensearch.migrations.replay;

import io.netty.util.ResourceLeakDetector;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonTransformingConsumer;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;
import org.opensearch.migrations.transform.JsonJoltTransformBuilder;
import org.opensearch.migrations.transform.JsonJoltTransformer;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

@Slf4j
public class AddCompressionEncodingTest {

    public static final byte BYTE_FILL_VALUE = (byte) '7';

    @Test
    public void addingCompressionRequestHeaderCompressesPayload() throws ExecutionException, InterruptedException, IOException {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);

        final var dummyAggregatedResponse = new TransformedTargetRequestAndResponse(null, 17, null,
                null, HttpRequestTransformationStatus.COMPLETED);
        var testPacketCapture = new TestCapturePacketToHttpHandler(Duration.ofMillis(100), dummyAggregatedResponse);
        var compressingTransformer = new HttpJsonTransformingConsumer(
                JsonJoltTransformer.newBuilder()
                        .addCannedOperation(JsonJoltTransformBuilder.CANNED_OPERATION.ADD_GZIP)
                        .build(), null, testPacketCapture, "TEST");

        final var payloadPartSize = 511;
        final var numParts = 1025;

        String sourceHeaders = "GET / HTTP/1.1\n" +
                "host: localhost\n" +
                "content-length: " + (numParts*payloadPartSize) + "\n";

        DiagnosticTrackableCompletableFuture<String,Void> tail =
                compressingTransformer.consumeBytes(sourceHeaders.getBytes(StandardCharsets.UTF_8))
                        .thenCompose(v-> compressingTransformer.consumeBytes("\n".getBytes(StandardCharsets.UTF_8)),
                                ()->"AddCompressionEncodingTest.compressingTransformer");
        final byte[] payloadPart = new byte[payloadPartSize];
        Arrays.fill(payloadPart, BYTE_FILL_VALUE);
        for (var i = new AtomicInteger(numParts); i.get()>0; i.decrementAndGet()) {
            tail = tail.thenCompose(v->compressingTransformer.consumeBytes(payloadPart),
                    ()->"AddCompressionEncodingTest.consumeBytes:"+i.get());
        }
        var fullyProcessedResponse =
                tail.thenCompose(v->compressingTransformer.finalizeRequest(),
                        ()->"AddCompressionEncodingTest.fullyProcessedResponse");
        fullyProcessedResponse.get();
        try (var bais = new ByteArrayInputStream(testPacketCapture.getBytesCaptured());
             var unzipStream = new GZIPInputStream(bais);
             var isr = new InputStreamReader(unzipStream, StandardCharsets.UTF_8);
             var br = new BufferedReader(isr)) {
            int counter = 0;
            int c;
            do {
                c = br.read();
                if (c == -1) {
                    break;
                } else {
                    Assertions.assertEquals(BYTE_FILL_VALUE, (byte) c);
                    ++counter;
                }
            } while (true);
            Assertions.assertEquals(numParts*payloadPartSize, counter);
        }
    }
}
