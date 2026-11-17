package org.opensearch.migrations.trafficcapture;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.protobuf.CodedOutputStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderedStreamLifecyleManagerTest {

    @Test
    void releasesAQueuedStreamWhenThePreviousPublicationFails() {
        var publicationFailure = new IllegalStateException("publication failed");
        var manager = new TestManager();
        var first = new TrackingHolder();
        var queued = new TrackingHolder();

        var firstPublication = manager.closeStream(first, 1);
        var queuedPublication = manager.closeStream(queued, 2);
        manager.firstPublication.completeExceptionally(publicationFailure);

        assertSame(
            publicationFailure,
            assertThrows(ExecutionException.class, firstPublication::get).getCause()
        );
        assertSame(
            publicationFailure,
            assertThrows(ExecutionException.class, queuedPublication::get).getCause()
        );
        assertEquals(1, manager.kickoffCalls.get());
        assertEquals(0, first.closeCalls.get());
        assertEquals(1, queued.closeCalls.get());
    }

    @Test
    void releasesAStreamWhenPublicationKickoffThrows() {
        var kickoffFailure = new IllegalStateException("kickoff failed");
        var manager = new OrderedStreamLifecyleManager<Void>() {
            @Override
            public CodedOutputStreamHolder createStream() {
                throw new UnsupportedOperationException();
            }

            @Override
            protected CompletableFuture<Void> kickoffCloseStream(
                CodedOutputStreamHolder outputStreamHolder,
                int index
            ) {
                throw kickoffFailure;
            }
        };
        var holder = new TrackingHolder();

        var observed = assertThrows(
            ExecutionException.class,
            () -> manager.closeStream(holder, 1).get()
        );

        assertSame(kickoffFailure, observed.getCause());
        assertEquals(1, holder.closeCalls.get());
    }

    private static class TestManager extends OrderedStreamLifecyleManager<Void> {
        private final CompletableFuture<Void> firstPublication = new CompletableFuture<>();
        private final AtomicInteger kickoffCalls = new AtomicInteger();

        @Override
        public CodedOutputStreamHolder createStream() {
            throw new UnsupportedOperationException();
        }

        @Override
        protected CompletableFuture<Void> kickoffCloseStream(
            CodedOutputStreamHolder outputStreamHolder,
            int index
        ) {
            kickoffCalls.incrementAndGet();
            return firstPublication;
        }
    }

    private static class TrackingHolder implements CodedOutputStreamHolder {
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final CodedOutputStream output =
            CodedOutputStream.newInstance(java.io.OutputStream.nullOutputStream());

        @Override
        public int getOutputStreamBytesLimit() {
            return -1;
        }

        @Override
        public CodedOutputStream getOutputStream() {
            return output;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }
}
