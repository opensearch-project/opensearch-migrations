package org.opensearch.migrations.replay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.ResourceLeakDetectorFactory;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonTransformer;
import org.opensearch.migrations.transform.JsonTransformBuilder;
import org.opensearch.migrations.transform.JsonTransformer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

@Slf4j
public class AddCompressionEncodingTest {

    @Test
    public void addingCompressionRequestHeaderCompressesPayload() throws ExecutionException, InterruptedException {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);

        final var dummyAggregatedResponse = new AggregatedRawResponse(17, null, null);
        var testPacketCapture = new TestCapturePacketToHttpHandler(Duration.ofMillis(100), dummyAggregatedResponse);
        var compressingTransformer = new HttpJsonTransformer(
                JsonTransformer.newBuilder()
                        .addCannedOperation(JsonTransformBuilder.CANNED_OPERATIONS.ADD_GZIP)
                        .build(), testPacketCapture);

        final var payloadPartSize = 1024;
        final var numParts = 1;
        ;
        String sourceHeaders = "GET / HTTP/1.1\n" +
                "host: localhost\n" +
                "content-length: " + (numParts*payloadPartSize) + "\n";

        CompletableFuture<Void> tail =
                compressingTransformer.consumeBytes(sourceHeaders.getBytes(StandardCharsets.UTF_8))
                        .thenCompose(v-> compressingTransformer.consumeBytes("\n".getBytes(StandardCharsets.UTF_8)));
        final byte[] payloadPart = new byte[payloadPartSize];
        Arrays.fill(payloadPart, (byte) '7');
        for (int i=numParts; i>0; --i) {
            tail = tail.thenCompose(v->compressingTransformer.consumeBytes(payloadPart));
        }
        CompletableFuture<AggregatedRawResponse> fullyProcessedResponse =
                tail.thenCompose(v->compressingTransformer.finalizeRequest());
        fullyProcessedResponse.get();
        var capturedTrafficString = testPacketCapture.getCapturedAsString();
        log.warn("capturedTrafficString="+capturedTrafficString);
    }
}
