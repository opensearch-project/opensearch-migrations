package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class SegmentReadSession implements AutoCloseable {
    private final LuceneLeafReader leaf;
    private final ConcurrentMap<Thread, Reader> readers = new ConcurrentHashMap<>();
    private volatile boolean closed;

    SegmentReadSession(LuceneLeafReader leaf) {
        this.leaf = leaf;
    }

    Reader forCurrentThread() {
        var thread = Thread.currentThread();
        var reader = readers.get(thread);
        if (reader == null) {
            reader = new Reader(leaf);
            if (!closed) {
                readers.put(thread, reader);
                if (closed) {
                    readers.remove(thread, reader);
                }
            }
        }
        return reader;
    }

    @Override
    public void close() {
        closed = true;
        readers.clear();
    }

    static final class Reader implements LuceneLeafReader.DocumentReader {
        private final LuceneLeafReader leaf;
        private LuceneLeafReader.DocumentReader documents;
        private LuceneLeafReader.NumericValueReader versions;
        private int lastVersionDocId = -1;

        private Reader(LuceneLeafReader leaf) {
            this.leaf = leaf;
        }

        @Override
        public LuceneDocument document(int docId) throws IOException {
            if (documents == null) {
                documents = leaf.openDocumentReader();
            }
            try {
                return documents.document(docId);
            } catch (IOException e) {
                documents = null;
                throw e;
            }
        }

        Long version(int docId) throws IOException {
            try {
                if (versions == null || docId < lastVersionDocId) {
                    versions = leaf.openNumericValueReader("_version");
                }
                Long value = versions.get(docId);
                lastVersionDocId = docId;
                return value;
            } catch (IOException | RuntimeException e) {
                versions = null;
                lastVersionDocId = -1;
                throw e;
            }
        }
    }
}
