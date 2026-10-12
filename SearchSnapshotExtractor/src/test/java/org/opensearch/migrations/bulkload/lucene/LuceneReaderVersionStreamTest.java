package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.opensearch.migrations.bulkload.common.DocumentChangeType;
import org.opensearch.migrations.bulkload.common.LuceneDocumentChange;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LuceneReaderVersionStreamTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Path INDEX = Path.of("test-index");

    @Test
    void versionsStayOrderedWhenStoredFieldsCompleteOutOfOrder() throws Exception {
        int documentCount = 257;
        var reader = reader(documentCount);
        var initialBatchStarted = new CountDownLatch(100);
        var completionOrder = new CopyOnWriteArrayList<Integer>();
        var documents = IntStream.range(0, documentCount).mapToObj(i -> document("d" + i)).toList();
        when(reader.document(anyInt())).thenAnswer(invocation -> {
            int docId = invocation.getArgument(0);
            if (docId < 100) {
                initialBatchStarted.countDown();
            }
            if (docId == 0) {
                assertTrue(initialBatchStarted.await(5, TimeUnit.SECONDS));
            }
            completionOrder.add(docId);
            return documents.get(docId);
        });
        var versions = IntStream.range(0, documentCount)
            .mapToObj(i -> i == documentCount - 1 ? Long.MAX_VALUE : i * 100_000_000_000_000L)
            .toArray(Long[]::new);
        var visits = cursors(reader, versions);

        var result = stream(reader, 100, 100, DocumentChangeType.INDEX).collectList().block(TIMEOUT);

        assertEquals(IntStream.range(100, 100 + documentCount).boxed().toList(),
            result.stream().map(doc -> doc.luceneDocNumber).toList());
        assertEquals(Arrays.asList(versions), result.stream().map(doc -> doc.version).toList());
        assertTrue(completionOrder.indexOf(1) < completionOrder.indexOf(0));
        assertEquals(List.of(IntStream.range(0, documentCount).boxed().toList()), visits);
        verify(reader, times(1)).openNumericValueReader("_version");
        verify(reader, never()).getNumericValue(anyInt(), anyString());
    }

    @Test
    void concurrentSubscriptionsUseIndependentCursorsAndStreams() throws Exception {
        var reader = reader(3);
        var visits = cursors(reader, new Long[] {7L, 8L, 9L});
        var documents = stream(reader, 0, 0, DocumentChangeType.INDEX);

        var result = Flux.merge(documents.subscribeOn(Schedulers.parallel()),
            documents.subscribeOn(Schedulers.parallel())).collectList().block(TIMEOUT);

        assertEquals(6, result.size());
        assertEquals(List.of(List.of(0, 1, 2), List.of(0, 1, 2)), visits);
        verify(reader, times(2)).openNumericValueReader("_version");
    }

    @Test
    void resubscriptionAfterCancellationStartsANewCursor() throws Exception {
        var reader = reader(3);
        var visits = cursors(reader, new Long[] {7L, 8L, 9L});
        var documents = stream(reader, 0, 0, DocumentChangeType.INDEX);

        assertEquals(1, documents.take(1).collectList().block(TIMEOUT).size());
        assertEquals(3, documents.collectList().block(TIMEOUT).size());
        assertEquals(List.of(0, 1, 2), visits.get(1));
        verify(reader, times(2)).openNumericValueReader("_version");
    }

    @Test
    void resumedSparseStreamUsesSegmentLocalIds() throws Exception {
        var reader = reader(5);
        var live = new BitSet();
        live.set(1);
        live.set(3);
        live.set(4);
        var visits = cursors(reader, new Long[] {1L, 2L, 3L, null, Long.MAX_VALUE});

        var result = LuceneReader.readDocsFromSegment(
            new ReaderAndBase(reader, 100, new BitSetConverter.FixedLengthBitSet(live)), 102,
            INDEX, DocumentChangeType.INDEX, null, false).collectList().block(TIMEOUT);

        assertEquals(List.of(103, 104), result.stream().map(doc -> doc.luceneDocNumber).toList());
        assertNull(result.get(0).version);
        assertEquals(Long.MAX_VALUE, result.get(1).version);
        assertEquals(List.of(List.of(3, 4)), visits);
    }

    @Test
    void differentSegmentsDoNotShareVersionCursors() throws Exception {
        var first = reader(1);
        var second = reader(1);
        cursors(first, new Long[] {7L});
        cursors(second, new Long[] {Long.MAX_VALUE});

        var result = Flux.concat(stream(first, 0, 0, DocumentChangeType.INDEX),
            stream(second, 1, 1, DocumentChangeType.INDEX)).collectList().block(TIMEOUT);

        assertEquals(List.of(7L, Long.MAX_VALUE), result.stream().map(doc -> doc.version).toList());
        verify(first).openNumericValueReader("_version");
        verify(second).openNumericValueReader("_version");
    }

    @Test
    void deletionStreamNeverOpensAVersionCursor() throws Exception {
        var reader = reader(2);

        var result = stream(reader, 0, 0, DocumentChangeType.DELETE).collectList().block(TIMEOUT);

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(doc -> doc.version == null));
        verify(reader, never()).openNumericValueReader(anyString());
        verify(reader, never()).getNumericValue(anyInt(), anyString());
    }

    @Test
    void missingVersionFieldKeepsDocuments() throws Exception {
        var reader = reader(2);
        cursors(reader, new Long[] {null, null});

        var result = stream(reader, 0, 0, DocumentChangeType.INDEX).collectList().block(TIMEOUT);

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(doc -> doc.version == null));
    }

    @Test
    void versionCursorFailureOnAValidDocumentFailsTheStream() throws Exception {
        var reader = reader(1);
        var expected = new IOException("unreadable version");
        when(reader.openNumericValueReader("_version")).thenReturn(docId -> {
            throw expected;
        });

        var failure = assertThrows(RuntimeException.class,
            () -> stream(reader, 0, 0, DocumentChangeType.INDEX).collectList().block(TIMEOUT));

        assertSame(expected, failure.getCause());
    }

    @Test
    void versionOpenFailureOnAValidDocumentFailsTheStream() throws Exception {
        var reader = reader(1);
        var expected = new IOException("unreadable version file");
        when(reader.openNumericValueReader("_version")).thenThrow(expected);

        var failure = assertThrows(RuntimeException.class,
            () -> stream(reader, 0, 0, DocumentChangeType.INDEX).collectList().block(TIMEOUT));

        assertSame(expected, failure.getCause());
    }

    @Test
    void skippedDocumentDiscardsVersionFailureAndReopensCursor() throws Exception {
        var reader = reader(2);
        var nestedDocument = document(null);
        when(reader.document(0)).thenReturn(nestedDocument);
        LuceneLeafReader.NumericValueReader failed = docId -> {
            throw new IOException("unreadable nested document version");
        };
        when(reader.openNumericValueReader("_version"))
            .thenReturn(failed, docId -> Long.MAX_VALUE);

        var result = stream(reader, 0, 0, DocumentChangeType.INDEX).collectList().block(TIMEOUT);

        assertEquals(1, result.size());
        assertEquals(1, result.get(0).luceneDocNumber);
        assertEquals(Long.MAX_VALUE, result.get(0).version);
        verify(reader, times(2)).openNumericValueReader("_version");
    }

    private static LuceneLeafReader reader(int count) throws IOException {
        var reader = mock(LuceneLeafReader.class);
        when(reader.maxDoc()).thenReturn(count);
        when(reader.openDocumentReader()).thenCallRealMethod();
        for (int docId = 0; docId < count; docId++) {
            var document = document("d" + docId);
            when(reader.document(docId)).thenReturn(document);
        }
        return reader;
    }

    private static List<List<Integer>> cursors(LuceneLeafReader reader, Long[] versions) throws IOException {
        var visits = new CopyOnWriteArrayList<List<Integer>>();
        when(reader.openNumericValueReader("_version")).thenAnswer(invocation -> {
            var cursorVisits = new ArrayList<Integer>();
            visits.add(cursorVisits);
            var last = new AtomicInteger(-1);
            var advancing = new AtomicBoolean();
            return (LuceneLeafReader.NumericValueReader) docId -> {
                assertTrue(advancing.compareAndSet(false, true), "cursor must not be shared by concurrent tasks");
                try {
                    assertTrue(docId > last.getAndSet(docId), "cursor must advance monotonically");
                    cursorVisits.add(docId);
                    return versions[docId];
                } finally {
                    advancing.set(false);
                }
            };
        });
        return visits;
    }

    private static LuceneDocument document(String idValue) {
        var source = mock(LuceneField.class);
        when(source.name()).thenReturn("_source");
        when(source.utf8Value()).thenReturn("{}".getBytes(StandardCharsets.UTF_8));
        if (idValue == null) {
            return () -> List.of(source);
        }
        var id = mock(LuceneField.class);
        when(id.name()).thenReturn("_id");
        when(id.asUid()).thenReturn(idValue);
        return () -> List.of(id, source);
    }

    private static Flux<LuceneDocumentChange> stream(
            LuceneLeafReader reader, int base, int start, DocumentChangeType operation) {
        return LuceneReader.readDocsFromSegment(new ReaderAndBase(reader, base, null), start,
            INDEX, operation, null, false);
    }
}
