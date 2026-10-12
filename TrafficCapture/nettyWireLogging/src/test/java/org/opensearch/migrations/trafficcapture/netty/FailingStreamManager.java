package org.opensearch.migrations.trafficcapture.netty;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.trafficcapture.CodedOutputStreamAndByteBufferWrapper;
import org.opensearch.migrations.trafficcapture.CodedOutputStreamHolder;
import org.opensearch.migrations.trafficcapture.OrderedStreamLifecyleManager;

import lombok.extern.slf4j.Slf4j;

@Slf4j
class FailingStreamManager extends OrderedStreamLifecyleManager implements AutoCloseable {
    AtomicInteger flushCount = new AtomicInteger();

    @Override
    public void close() {}

    @Override
    public CodedOutputStreamAndByteBufferWrapper createStream() {
        return new CodedOutputStreamAndByteBufferWrapper(1024 * 1024);
    }

    @Override
    public CompletableFuture<Object> kickoffCloseStream(CodedOutputStreamHolder outputStreamHolder, int index) {
        flushCount.incrementAndGet();
        return CompletableFuture.failedFuture(
            new RuntimeException("Simulated RecordTooLargeException")
        );
    }
}
