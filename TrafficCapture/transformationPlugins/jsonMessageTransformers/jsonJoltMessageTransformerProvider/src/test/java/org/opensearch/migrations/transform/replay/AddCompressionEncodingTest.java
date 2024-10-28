package org.opensearch.migrations.transform.replay;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.TestCapturePacketToHttpHandler;
import org.opensearch.migrations.replay.Utils;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonTransformingConsumer;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.transform.JsonJoltTransformBuilder;
import org.opensearch.migrations.transform.JsonJoltTransformer;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Slf4j
@WrapWithNettyLeakDetection(repetitions = 1)
public class AddCompressionEncodingTest extends InstrumentationTest {

    public static final byte BYTE_FILL_VALUE = (byte) '7';

    @Test
    @Tag("longTest")
    public void addingCompressionRequestHeaderCompressesPayload() throws ExecutionException, InterruptedException,
        IOException {
        final var dummyAggregatedResponse = new AggregatedRawResponse(null, 17, Duration.ZERO, List.of(), null);
        var testPacketCapture = new TestCapturePacketToHttpHandler(Duration.ofMillis(100), dummyAggregatedResponse);
        var compressingTransformer = new HttpJsonTransformingConsumer<>(
            JsonJoltTransformer.newBuilder()
                .addCannedOperation(JsonJoltTransformBuilder.CANNED_OPERATION.ADD_GZIP)
                .build(),
            null,
            testPacketCapture,
            rootContext.getTestConnectionRequestContext(0)
        );

        final var payloadPartSize = 511;
        final var numParts = 1025;

        String sourceHeaders = "GET / HTTP/1.1\n"
            + "host: localhost\n"
            + "content-length: "
            + (numParts * payloadPartSize)
            + "\n";

        var tail = compressingTransformer.consumeBytes(sourceHeaders.getBytes(StandardCharsets.UTF_8))
            .thenCompose(
                v -> compressingTransformer.consumeBytes("\n".getBytes(StandardCharsets.UTF_8)),
                () -> "AddCompressionEncodingTest.compressingTransformer"
            );
        final byte[] payloadPart = new byte[payloadPartSize];
        Arrays.fill(payloadPart, BYTE_FILL_VALUE);
        for (var i = new AtomicInteger(numParts); i.get() > 0; i.decrementAndGet()) {
            tail = tail.thenCompose(
                v -> compressingTransformer.consumeBytes(payloadPart),
                () -> "AddCompressionEncodingTest.consumeBytes:" + i.get()
            );
        }
        var fullyProcessedResponse = tail.thenCompose(
            v -> compressingTransformer.finalizeRequest(),
            () -> "AddCompressionEncodingTest.fullyProcessedResponse"
        );
        fullyProcessedResponse.get();

        EmbeddedChannel channel = new EmbeddedChannel(
            new HttpServerCodec(),
            new HttpObjectAggregator(Utils.MAX_PAYLOAD_BYTES_TO_PRINT)  // Set max content length if needed
        );

        channel.writeInbound(Unpooled.wrappedBuffer(testPacketCapture.getBytesCaptured()));
        var compressedRequest = ((FullHttpRequest) channel.readInbound());
        var compressedByteArr = new byte[compressedRequest.content().readableBytes()];
        compressedRequest.content().getBytes(0, compressedByteArr);
        try (
            var bais = new ByteArrayInputStream(compressedByteArr);
            var unzipStream = new GZIPInputStream(bais);
            var isr = new InputStreamReader(unzipStream, StandardCharsets.UTF_8);
            var br = new BufferedReader(isr)
        ) {
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
            Assertions.assertEquals(numParts * payloadPartSize, counter);
        }
        compressedRequest.release();
    }
}
