package org.opensearch.migrations.replay;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Assertions;

import org.opensearch.migrations.replay.datahandlers.IPacketFinalizingConsumer;
import org.opensearch.migrations.replay.util.TextTrackedFuture;
import org.opensearch.migrations.replay.util.TrackedFuture;

import io.netty.buffer.ByteBuf;
import lombok.Getter;
import lombok.Lombok;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TestCapturePacketToHttpHandler implements IPacketFinalizingConsumer<AggregatedRawResponse> {
    private final Duration consumeDuration;
    private final AtomicInteger numFinalizations;
    @Getter
    private byte[] bytesCaptured;

    @Getter
    private final AtomicInteger numConsumes;
    private final AggregatedRawResponse dummyAggregatedResponse;
    ByteArrayOutputStream byteArrayOutputStream;

    public TestCapturePacketToHttpHandler(
        Duration consumeDuration,
        @NonNull AggregatedRawResponse dummyAggregatedResponse
    ) {
        this.consumeDuration = consumeDuration;
        this.numFinalizations = new AtomicInteger();
        this.numConsumes = new AtomicInteger();
        this.dummyAggregatedResponse = dummyAggregatedResponse;
        byteArrayOutputStream = new ByteArrayOutputStream();
    }

    @Override
    public TrackedFuture<String, Void> consumeBytes(ByteBuf nextRequestPacket) {
        numConsumes.incrementAndGet();
        log.info("incoming buffer refcnt=" + nextRequestPacket.refCnt());
        var duplicatedPacket = nextRequestPacket.retainedDuplicate();
        return new TrackedFuture<>(CompletableFuture.runAsync(() -> {
            try {
                log.info("Running async future for " + nextRequestPacket);
                Thread.sleep(consumeDuration.toMillis());
                log.info("woke up from sleeping for " + nextRequestPacket);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw Lombok.sneakyThrow(e);
            }
            try {
                log.info("At the time of committing the buffer, refcnt=" + duplicatedPacket.refCnt());
                duplicatedPacket.readBytes(byteArrayOutputStream, nextRequestPacket.readableBytes());
                duplicatedPacket.release();
            } catch (IOException e) {
                throw Lombok.sneakyThrow(e);
            }
        }), () -> "TestCapturePacketToHttpHandler.consumeBytes");
    }

    @Override
    public TrackedFuture<String, AggregatedRawResponse> finalizeRequest() {
        numFinalizations.incrementAndGet();
        Assertions.assertEquals(1, numFinalizations.get());
        bytesCaptured = byteArrayOutputStream.toByteArray();
        return TextTrackedFuture.completedFuture(dummyAggregatedResponse, () -> "TestCapturePacketToHttpHandler.dummy");
    }

    public String getCapturedAsString() {
        return new String(bytesCaptured, StandardCharsets.UTF_8);

    }
}
