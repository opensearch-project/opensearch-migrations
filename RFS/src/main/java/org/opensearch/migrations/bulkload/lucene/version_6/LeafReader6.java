package org.opensearch.migrations.bulkload.lucene.version_6;

import java.io.IOException;
import java.util.BitSet;

import org.opensearch.migrations.bulkload.lucene.LiveDocsConverter;
import org.opensearch.migrations.bulkload.lucene.LuceneLeafReader;

import lombok.Getter;
import shadow.lucene6.org.apache.lucene.index.LeafReader;
import shadow.lucene6.org.apache.lucene.index.SegmentReader;
import shadow.lucene6.org.apache.lucene.util.Bits;
import shadow.lucene6.org.apache.lucene.util.FixedBitSet;
import shadow.lucene6.org.apache.lucene.util.SparseFixedBitSet;

public class LeafReader6 implements LuceneLeafReader {

    private final LeafReader wrapped;
    @Getter
    private final BitSet liveDocs;

    public LeafReader6(LeafReader wrapped) {
        this.wrapped = wrapped;
        this.liveDocs = convertLiveDocs(wrapped.getLiveDocs());
    }

    private static BitSet convertLiveDocs(Bits bits) {
        return LiveDocsConverter.convertLiveDocs(
            bits,
            FixedBitSet.class,
            SparseFixedBitSet.class,
            obj -> ((FixedBitSet) obj).getBits(),
            obj -> ((Bits) obj).length(),
            idx -> bits != null && bits.get(idx),
            idx -> ((SparseFixedBitSet) bits).nextSetBit(idx)
        );
    }

    public Document6 document(int luceneDocId) throws IOException {
        return new Document6(wrapped.document(luceneDocId));
    }

    public int maxDoc() {
        return wrapped.maxDoc();
    }

    public String getContextString() {
        return wrapped.getContext().toString();
    }

    private SegmentReader getSegmentReader() {
        if (wrapped instanceof SegmentReader) {
            return (SegmentReader) wrapped;
        }
        throw new IllegalStateException("Expected SegmentReader but got " + wrapped.getClass());
    }

    public String getSegmentName() { 
        return getSegmentReader()
            .getSegmentName();
    }

    public String getSegmentInfoString() {
        return getSegmentReader()
            .getSegmentInfo()
            .toString();
    }

    public String toString() {
        return wrapped.toString();
    }
}
