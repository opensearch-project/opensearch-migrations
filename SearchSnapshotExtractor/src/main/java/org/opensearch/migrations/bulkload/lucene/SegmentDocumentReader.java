package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class SegmentDocumentReader implements LuceneLeafReader.DocumentReader, AutoCloseable {
    private final LuceneLeafReader leaf;
    private final ConcurrentMap<Thread, LuceneLeafReader.DocumentReader> readers = new ConcurrentHashMap<>();
    private volatile boolean closed;

    SegmentDocumentReader(LuceneLeafReader leaf) {
        this.leaf = leaf;
    }

    @Override
    public LuceneDocument document(int docId) throws IOException {
        var thread = Thread.currentThread();
        var reader = readers.get(thread);
        if (reader == null) {
            reader = leaf.openDocumentReader();
            if (!closed) {
                readers.put(thread, reader);
                if (closed) {
                    readers.remove(thread, reader);
                }
            }
        }
        try {
            return reader.document(docId);
        } catch (IOException e) {
            readers.remove(thread, reader);
            throw e;
        }
    }

    @Override
    public void close() {
        closed = true;
        readers.clear();
    }
}
