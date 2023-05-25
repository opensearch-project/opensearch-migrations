package org.opensearch.migrations.replay;

import io.netty.buffer.ByteBuf;
import lombok.Getter;
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
public class TestCapturePacketToHttpHandler implements IPacketToHttpHandler {
    private final Duration consumeDuration;
    private final AtomicInteger numFinalizations;
    @Getter
    private byte[] bytesCaptured;
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
        log.info("incoming buffer refcnt="+nextRequestPacket.refCnt());
        var duplicatedPacket = nextRequestPacket.duplicate().retain();
        return CompletableFuture.runAsync(() -> {
            try {
                log.info("Running async future for " + nextRequestPacket);
                Thread.sleep(consumeDuration.toMillis());
                log.info("woke up from sleeping for " + nextRequestPacket);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            try {
                log.info("At the time of committing the buffer, refcnt="+duplicatedPacket.refCnt());
                duplicatedPacket.readBytes(byteArrayOutputStream, nextRequestPacket.readableBytes());
                duplicatedPacket.release();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public CompletableFuture<AggregatedRawResponse> finalizeRequest() {
        numFinalizations.incrementAndGet();
        Assertions.assertEquals(1, numFinalizations.get());
        bytesCaptured = byteArrayOutputStream.toByteArray();
        return CompletableFuture.completedFuture(dummyAggregatedResponse);
    }

    public String getCapturedAsString() {
        return new String(bytesCaptured, StandardCharsets.UTF_8);

    }
}
