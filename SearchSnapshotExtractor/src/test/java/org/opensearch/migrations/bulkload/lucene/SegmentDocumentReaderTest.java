package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SegmentDocumentReaderTest {
    private static final LuceneDocument DOCUMENT = List::of;

    @Test
    void sameThreadReusesReaderForArbitraryDocumentOrder() throws Exception {
        var leaf = mock(LuceneLeafReader.class);
        var owner = Thread.currentThread();
        var visits = new ArrayList<Integer>();
        when(leaf.openDocumentReader()).thenReturn(docId -> {
            assertSame(owner, Thread.currentThread());
            visits.add(docId);
            return DOCUMENT;
        });
        try (var reader = new SegmentDocumentReader(leaf)) {
            for (int docId : List.of(9, 1, 9, 0)) {
                assertSame(DOCUMENT, reader.document(docId));
            }
        }

        assertEquals(List.of(9, 1, 9, 0), visits);
        verify(leaf, times(1)).openDocumentReader();
    }

    @Test
    void concurrentWorkersEachCreateAndReuseTheirOwnReader() throws Exception {
        var leaf = mock(LuceneLeafReader.class);
        var factoriesStarted = new CountDownLatch(2);
        var allowFactories = new CountDownLatch(1);
        when(leaf.openDocumentReader()).thenAnswer(invocation -> {
            var owner = Thread.currentThread();
            var inUse = new AtomicBoolean();
            factoriesStarted.countDown();
            assertTrue(allowFactories.await(5, TimeUnit.SECONDS));
            return (LuceneLeafReader.DocumentReader) docId -> {
                assertSame(owner, Thread.currentThread(), "a stored-fields reader must stay on its opening thread");
                assertTrue(inUse.compareAndSet(false, true));
                try {
                    return DOCUMENT;
                } finally {
                    inUse.set(false);
                }
            };
        });
        try (var workers = Executors.newFixedThreadPool(2); var reader = new SegmentDocumentReader(leaf)) {
            var first = workers.submit(() -> readRepeatedly(reader));
            var second = workers.submit(() -> readRepeatedly(reader));
            try {
                assertTrue(factoriesStarted.await(5, TimeUnit.SECONDS));
            } finally {
                allowFactories.countDown();
            }
            assertEquals(20, first.get(5, TimeUnit.SECONDS));
            assertEquals(20, second.get(5, TimeUnit.SECONDS));
        }

        verify(leaf, times(2)).openDocumentReader();
    }

    @Test
    void factoryFailureIsNotCached() throws Exception {
        var leaf = mock(LuceneLeafReader.class);
        var failure = new IOException("cannot open stored fields");
        when(leaf.openDocumentReader()).thenThrow(failure).thenReturn(docId -> DOCUMENT);
        try (var reader = new SegmentDocumentReader(leaf)) {
            assertSame(failure, assertThrows(IOException.class, () -> reader.document(0)));
            assertSame(DOCUMENT, reader.document(1));
            assertSame(DOCUMENT, reader.document(0));
        }

        verify(leaf, times(2)).openDocumentReader();
    }

    @Test
    void readFailureEvictsPotentiallyCorruptedDecoder() throws Exception {
        var leaf = mock(LuceneLeafReader.class);
        var failure = new IOException("cannot decompress chunk");
        LuceneLeafReader.DocumentReader broken = docId -> {
            throw failure;
        };
        when(leaf.openDocumentReader()).thenReturn(broken).thenReturn(docId -> DOCUMENT);
        try (var reader = new SegmentDocumentReader(leaf)) {
            assertSame(failure, assertThrows(IOException.class, () -> reader.document(0)));
            assertSame(DOCUMENT, reader.document(1));
            assertSame(DOCUMENT, reader.document(0));
        }

        verify(leaf, times(2)).openDocumentReader();
    }

    @Test
    void completionDoesNotRetainReaderWhoseFactoryWasInFlight() throws Exception {
        var leaf = mock(LuceneLeafReader.class);
        var factoryEntered = new CountDownLatch(1);
        var releaseFactory = new CountDownLatch(1);
        var factories = new AtomicInteger();
        when(leaf.openDocumentReader()).thenAnswer(invocation -> {
            if (factories.incrementAndGet() == 1) {
                factoryEntered.countDown();
                assertTrue(releaseFactory.await(5, TimeUnit.SECONDS));
            }
            return (LuceneLeafReader.DocumentReader) docId -> DOCUMENT;
        });
        try (var worker = Executors.newSingleThreadExecutor(); var reader = new SegmentDocumentReader(leaf)) {
            var first = worker.submit(() -> reader.document(0));
            try {
                assertTrue(factoryEntered.await(5, TimeUnit.SECONDS));
                reader.close();
            } finally {
                releaseFactory.countDown();
            }
            assertSame(DOCUMENT, first.get(5, TimeUnit.SECONDS));
            assertSame(DOCUMENT, worker.submit(() -> reader.document(1)).get(5, TimeUnit.SECONDS));
            assertSame(DOCUMENT, worker.submit(() -> reader.document(2)).get(5, TimeUnit.SECONDS));
        }

        assertEquals(3, factories.get(), "late factory completion must not repopulate the closed cache");
    }

    @Test
    void completionReleasesCachedReaderWithoutInvalidatingAnInFlightRead() throws Exception {
        var leaf = mock(LuceneLeafReader.class);
        var readEntered = new CountDownLatch(1);
        var releaseRead = new CountDownLatch(1);
        when(leaf.openDocumentReader()).thenAnswer(invocation -> (LuceneLeafReader.DocumentReader) docId -> {
            if (docId == 0) {
                readEntered.countDown();
                try {
                    assertTrue(releaseRead.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            return DOCUMENT;
        });
        try (var worker = Executors.newSingleThreadExecutor(); var reader = new SegmentDocumentReader(leaf)) {
            var first = worker.submit(() -> reader.document(0));
            try {
                assertTrue(readEntered.await(5, TimeUnit.SECONDS));
                reader.close();
            } finally {
                releaseRead.countDown();
            }
            assertSame(DOCUMENT, first.get(5, TimeUnit.SECONDS));
            assertSame(DOCUMENT, worker.submit(() -> reader.document(1)).get(5, TimeUnit.SECONDS));
        }

        verify(leaf, times(2)).openDocumentReader();
    }

    @Test
    void differentSubscriptionsDoNotShareThreadReaders() throws Exception {
        var leaf = mock(LuceneLeafReader.class);
        when(leaf.openDocumentReader()).thenReturn(docId -> DOCUMENT);
        try (var first = new SegmentDocumentReader(leaf); var second = new SegmentDocumentReader(leaf)) {
            first.document(0);
            second.document(0);
            first.document(1);
            second.document(1);
        }

        verify(leaf, times(2)).openDocumentReader();
    }

    private static int readRepeatedly(SegmentDocumentReader reader) throws IOException {
        for (int i = 19; i >= 0; i--) {
            assertSame(DOCUMENT, reader.document(i));
        }
        return 20;
    }
}
