package org.opensearch.migrations.trafficcapture.netty;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.google.protobuf.CodedOutputStream;

import org.opensearch.migrations.trafficcapture.CodedOutputStreamAndByteBufferWrapper;
import org.opensearch.migrations.trafficcapture.CodedOutputStreamHolder;
import org.opensearch.migrations.trafficcapture.OrderedStreamLifecyleManager;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class TestStreamManager extends OrderedStreamLifecyleManager implements AutoCloseable {
    AtomicReference<ByteBuffer> byteBufferAtomicReference = new AtomicReference<>();
    AtomicInteger flushCount = new AtomicInteger();

    @Override
    public void close() {}

    @Override
    public CodedOutputStreamAndByteBufferWrapper createStream() {
        return new CodedOutputStreamAndByteBufferWrapper(1024 * 1024);
    }

    @SneakyThrows
    @Override
    public CompletableFuture<Object> kickoffCloseStream(CodedOutputStreamHolder outputStreamHolder, int index) {
        if (!(outputStreamHolder instanceof CodedOutputStreamAndByteBufferWrapper)) {
            throw new IllegalStateException(
                "Unknown outputStreamHolder sent back to StreamManager: " + outputStreamHolder
            );
        }
        var osh = (CodedOutputStreamAndByteBufferWrapper) outputStreamHolder;
        CodedOutputStream cos = osh.getOutputStream();

        cos.flush();
        byteBufferAtomicReference.set(osh.getByteBuffer().flip().asReadOnlyBuffer());
        log.trace("byteBufferAtomicReference.get=" + byteBufferAtomicReference.get());

        return CompletableFuture.completedFuture(flushCount.incrementAndGet());
    }
}
