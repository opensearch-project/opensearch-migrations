package org.opensearch.migrations.bulkload.lucene.version_5;

import java.io.IOException;

import org.opensearch.migrations.bulkload.lucene.GenericStreamingFieldPostings;

import shadow.lucene5.org.apache.lucene.index.PostingsEnum;

/**
 * Bridges Lucene-5's shadowed {@link PostingsEnum} to the version-independent
 * {@link GenericStreamingFieldPostings.PostingsCursor} abstraction. Used by
 * both streaming postings paths (positional + freq-only multi-term).
 */
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
