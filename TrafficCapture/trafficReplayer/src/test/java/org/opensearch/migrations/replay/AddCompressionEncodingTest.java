package org.opensearch.migrations.replay;

import io.netty.util.ResourceLeakDetector;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonTransformingConsumer;
import org.opensearch.migrations.transform.JoltJsonTransformBuilder;
import org.opensearch.migrations.transform.JoltJsonTransformer;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.zip.GZIPInputStream;

@Slf4j
public class AddCompressionEncodingTest {

    public static final byte BYTE_FILL_VALUE = (byte) '7';

    @Test
    public void addingCompressionRequestHeaderCompressesPayload() throws ExecutionException, InterruptedException, IOException {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);

        final var dummyAggregatedResponse = new AggregatedTransformedResponse(17, null,
                null, AggregatedTransformedResponse.HttpRequestTransformationStatus.COMPLETED);
        var testPacketCapture = new TestCapturePacketToHttpHandler(Duration.ofMillis(100), dummyAggregatedResponse);
        var compressingTransformer = new HttpJsonTransformingConsumer(
                JoltJsonTransformer.newBuilder()
                        .addCannedOperation(JoltJsonTransformBuilder.CANNED_OPERATION.ADD_GZIP)
                        .build(), testPacketCapture);

        final var payloadPartSize = 511;
        final var numParts = 1025;

        String sourceHeaders = "GET / HTTP/1.1\n" +
                "host: localhost\n" +
                "content-length: " + (numParts*payloadPartSize) + "\n";

        CompletableFuture<Void> tail =
                compressingTransformer.consumeBytes(sourceHeaders.getBytes(StandardCharsets.UTF_8))
                        .thenCompose(v-> compressingTransformer.consumeBytes("\n".getBytes(StandardCharsets.UTF_8)));
        final byte[] payloadPart = new byte[payloadPartSize];
        Arrays.fill(payloadPart, BYTE_FILL_VALUE);
        for (int i=numParts; i>0; --i) {
            tail = tail.thenCompose(v->compressingTransformer.consumeBytes(payloadPart));
        }
        var fullyProcessedResponse =
                tail.thenCompose(v->compressingTransformer.finalizeRequest());
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
