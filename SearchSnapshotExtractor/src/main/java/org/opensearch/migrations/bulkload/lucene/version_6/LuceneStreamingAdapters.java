package org.opensearch.migrations.bulkload.lucene.version_6;

import java.io.IOException;

import org.opensearch.migrations.bulkload.lucene.GenericStreamingFieldPostings;

import shadow.lucene6.org.apache.lucene.index.PostingsEnum;

final class LuceneStreamingAdapters {

    private LuceneStreamingAdapters() {}

    static GenericStreamingFieldPostings.PostingsCursor wrap(final PostingsEnum pe) {
        return new GenericStreamingFieldPostings.PostingsCursor() {
            @Override public int nextDoc() throws IOException { return pe.nextDoc(); }
            @Override public int advance(int target) throws IOException { return pe.advance(target); }
            @Override public int freq() throws IOException { return pe.freq(); }
            @Override public int nextPosition() throws IOException { return pe.nextPosition(); }
            @Override public int startOffset() throws IOException { return pe.startOffset(); }
            @Override public int endOffset() throws IOException { return pe.endOffset(); }
        };
    }
}
