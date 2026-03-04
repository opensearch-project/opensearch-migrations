package org.opensearch.migrations.bulkload.lucene.version_6;

import java.io.IOException;

import org.opensearch.migrations.bulkload.lucene.BitSetConverter;
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
    private final BitSetConverter.FixedLengthBitSet liveDocs;

    public LeafReader6(LeafReader wrapped) {
        this.wrapped = wrapped;
        this.liveDocs = convertLiveDocs(wrapped.getLiveDocs());
    }

    private static BitSetConverter.FixedLengthBitSet convertLiveDocs(Bits bits) {
        return BitSetConverter.convert(
            bits,
            FixedBitSet.class,
            SparseFixedBitSet.class,
            FixedBitSet::getBits,
            Bits::length,
            Bits::get,
            sparseBits -> sparseBits::nextSetBit
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
