package org.opensearch.migrations.replay;

import io.netty.buffer.ByteBuf;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.opensearch.migrations.replay.datahandlers.IPacketToHttpHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
class TestCapturePacketToHttpHandler implements IPacketToHttpHandler {
    private final Duration consumeDuration;
    private final AtomicInteger numFinalizations;
    private String capturedCompleteString;
    private final AggregatedRawResponse dummyAggregatedResponse;
    ByteArrayOutputStream byteArrayOutputStream;

    public TestCapturePacketToHttpHandler(Duration consumeDuration,
                                          AggregatedRawResponse dummyAggregatedResponse) {
        this.consumeDuration = consumeDuration;
        this.numFinalizations = new AtomicInteger();
        this.dummyAggregatedResponse = dummyAggregatedResponse;
        byteArrayOutputStream = new ByteArrayOutputStream();
    }

    @Override
    public CompletableFuture<Void> consumeBytes(ByteBuf nextRequestPacket) {
        return CompletableFuture.runAsync(() -> {
            try {
                log.info("Running async future for " + nextRequestPacket);
                Thread.sleep(consumeDuration.toMillis());
                log.info("woke up from sleeping for " + nextRequestPacket);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            try {
                nextRequestPacket.duplicate()
                        .readBytes(byteArrayOutputStream, nextRequestPacket.readableBytes());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public CompletableFuture<AggregatedRawResponse> finalizeRequest() {
        numFinalizations.incrementAndGet();
        Assertions.assertEquals(1, numFinalizations.get());
        var bytes = byteArrayOutputStream.toByteArray();
        capturedCompleteString = new String(bytes, StandardCharsets.UTF_8);
        return CompletableFuture.completedFuture(dummyAggregatedResponse);
    }

    public String getCapturedAsString() {
        return capturedCompleteString;
    }
}
