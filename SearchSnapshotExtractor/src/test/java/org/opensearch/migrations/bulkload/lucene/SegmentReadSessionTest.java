package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SegmentReadSessionTest {
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
        try (var reader = new SegmentReadSession(leaf)) {
            for (int docId : List.of(9, 1, 9, 0)) {
                assertSame(DOCUMENT, reader.forCurrentThread().document(docId));
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
        try (var workers = Executors.newFixedThreadPool(2); var reader = new SegmentReadSession(leaf)) {
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
        try (var reader = new SegmentReadSession(leaf)) {
            assertSame(failure, assertThrows(IOException.class, () -> reader.forCurrentThread().document(0)));
            assertSame(DOCUMENT, reader.forCurrentThread().document(1));
            assertSame(DOCUMENT, reader.forCurrentThread().document(0));
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
        try (var reader = new SegmentReadSession(leaf)) {
            assertSame(failure, assertThrows(IOException.class, () -> reader.forCurrentThread().document(0)));
            assertSame(DOCUMENT, reader.forCurrentThread().document(1));
            assertSame(DOCUMENT, reader.forCurrentThread().document(0));
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
        try (var worker = Executors.newSingleThreadExecutor(); var reader = new SegmentReadSession(leaf)) {
            var first = worker.submit(() -> reader.forCurrentThread().document(0));
            try {
                assertTrue(factoryEntered.await(5, TimeUnit.SECONDS));
                reader.close();
            } finally {
                releaseFactory.countDown();
            }
            assertSame(DOCUMENT, first.get(5, TimeUnit.SECONDS));
            assertSame(DOCUMENT, worker.submit(() -> reader.forCurrentThread().document(1)).get(5, TimeUnit.SECONDS));
            assertSame(DOCUMENT, worker.submit(() -> reader.forCurrentThread().document(2)).get(5, TimeUnit.SECONDS));
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
        try (var worker = Executors.newSingleThreadExecutor(); var reader = new SegmentReadSession(leaf)) {
            var first = worker.submit(() -> reader.forCurrentThread().document(0));
            try {
                assertTrue(readEntered.await(5, TimeUnit.SECONDS));
                reader.close();
            } finally {
                releaseRead.countDown();
            }
            assertSame(DOCUMENT, first.get(5, TimeUnit.SECONDS));
            assertSame(DOCUMENT, worker.submit(() -> reader.forCurrentThread().document(1)).get(5, TimeUnit.SECONDS));
        }

        verify(leaf, times(2)).openDocumentReader();
    }

    @Test
    void differentSubscriptionsDoNotShareThreadReaders() throws Exception {
        var leaf = mock(LuceneLeafReader.class);
        when(leaf.openDocumentReader()).thenReturn(docId -> DOCUMENT);
        try (var first = new SegmentReadSession(leaf); var second = new SegmentReadSession(leaf)) {
            assertNotSame(first.forCurrentThread(), second.forCurrentThread());
            first.forCurrentThread().document(0);
            second.forCurrentThread().document(0);
            first.forCurrentThread().document(1);
            second.forCurrentThread().document(1);
        }

        verify(leaf, times(2)).openDocumentReader();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("versionReadOrders")
    void numericCursorsReopenOnlyForBackwardReads(String name, List<Integer> order) throws Exception {
        var leaf = mock(LuceneLeafReader.class);
        Long[] values = {0L, 7L, Long.MAX_VALUE, null, 9_007_199_254_740_993L};
        var opens = new AtomicInteger();
        var owner = Thread.currentThread();
        when(leaf.openNumericValueReader("_version")).thenAnswer(invocation -> {
            assertSame(owner, Thread.currentThread());
            opens.incrementAndGet();
            var last = new AtomicInteger(-1);
            return (LuceneLeafReader.NumericValueReader) docId -> {
                assertSame(owner, Thread.currentThread());
                assertTrue(docId >= last.getAndSet(docId));
                return values[docId];
            };
        });
        try (var session = new SegmentReadSession(leaf)) {
            var reader = session.forCurrentThread();
            var actual = new ArrayList<Long>();
            for (int docId : order) {
                assertSame(reader, session.forCurrentThread());
                actual.add(reader.version(docId));
            }
            assertEquals(order.stream().map(docId -> values[docId]).toList(), actual);
        }

        int expectedOpens = 1;
        for (int i = 1; i < order.size(); i++) {
            if (order.get(i) < order.get(i - 1)) {
                expectedOpens++;
            }
        }
        assertEquals(expectedOpens, opens.get());
        verify(leaf, never()).openDocumentReader();
    }

    static Stream<Arguments> versionReadOrders() {
        return Stream.of(
            Arguments.of("ascending", List.of(0, 1, 2, 3, 4)),
            Arguments.of("descending", List.of(4, 3, 2, 1, 0)),
            Arguments.of("shuffled", List.of(2, 0, 4, 1, 3)),
            Arguments.of("repeated", List.of(2, 2, 3, 3, 0, 0, 4, 4)));
    }

    @Test
    void numericReadFailureReopensCursorAndPreservesStoredReader() throws Exception {
        var leaf = mock(LuceneLeafReader.class);
        var failure = new IOException("cannot read version");
        when(leaf.openDocumentReader()).thenReturn(docId -> DOCUMENT);
        LuceneLeafReader.NumericValueReader broken = docId -> {
            throw failure;
        };
        when(leaf.openNumericValueReader("_version")).thenReturn(broken).thenReturn(docId -> Long.MAX_VALUE);
        try (var session = new SegmentReadSession(leaf)) {
            var reader = session.forCurrentThread();
            reader.document(0);
            assertSame(failure, assertThrows(IOException.class, () -> reader.version(0)));
            assertSame(DOCUMENT, reader.document(1));
            assertEquals(Long.MAX_VALUE, reader.version(1));
        }

        verify(leaf, times(1)).openDocumentReader();
        verify(leaf, times(2)).openNumericValueReader("_version");
    }

    @Test
    void numericFactoryFailureIsNotCached() throws Exception {
        var leaf = mock(LuceneLeafReader.class);
        var failure = new IOException("cannot open versions");
        when(leaf.openNumericValueReader("_version")).thenThrow(failure).thenReturn(docId -> 7L);
        try (var session = new SegmentReadSession(leaf)) {
            var reader = session.forCurrentThread();
            assertSame(failure, assertThrows(IOException.class, () -> reader.version(0)));
            assertEquals(7L, reader.version(1));
            assertEquals(7L, reader.version(1));
        }

        verify(leaf, times(2)).openNumericValueReader("_version");
    }

    @Test
    void storedReadFailureDoesNotDiscardNumericCursor() throws Exception {
        var leaf = mock(LuceneLeafReader.class);
        var failure = new IOException("cannot read stored fields");
        LuceneLeafReader.DocumentReader broken = docId -> {
            throw failure;
        };
        when(leaf.openDocumentReader()).thenReturn(broken).thenReturn(docId -> DOCUMENT);
        when(leaf.openNumericValueReader("_version")).thenReturn(docId -> (long) docId);
        try (var session = new SegmentReadSession(leaf)) {
            var reader = session.forCurrentThread();
            assertEquals(0L, reader.version(0));
            assertSame(failure, assertThrows(IOException.class, () -> reader.document(0)));
            assertSame(DOCUMENT, reader.document(1));
            assertEquals(1L, reader.version(1));
        }

        verify(leaf, times(2)).openDocumentReader();
        verify(leaf, times(1)).openNumericValueReader("_version");
    }

    private static int readRepeatedly(SegmentReadSession reader) throws IOException {
        for (int i = 19; i >= 0; i--) {
            assertSame(DOCUMENT, reader.forCurrentThread().document(i));
        }
        return 20;
    }
}
