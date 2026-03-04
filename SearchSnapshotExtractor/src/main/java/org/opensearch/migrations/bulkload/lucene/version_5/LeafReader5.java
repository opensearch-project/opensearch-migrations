package org.opensearch.migrations.bulkload.lucene.version_5;

import java.io.IOException;

import org.opensearch.migrations.bulkload.lucene.BitSetConverter;
import org.opensearch.migrations.bulkload.lucene.LuceneLeafReader;

import lombok.Getter;
import shadow.lucene5.org.apache.lucene.index.LeafReader;
import shadow.lucene5.org.apache.lucene.index.SegmentReader;
import shadow.lucene5.org.apache.lucene.util.Bits;
import shadow.lucene5.org.apache.lucene.util.FixedBitSet;
import shadow.lucene5.org.apache.lucene.util.SparseFixedBitSet;

public class LeafReader5 implements LuceneLeafReader {

    private final LeafReader wrapped;
    @Getter
    private final BitSetConverter.FixedLengthBitSet liveDocs;

    public LeafReader5(LeafReader wrapped) {
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

    public Document5 document(int luceneDocId) throws IOException {
        return new Document5(wrapped.document(luceneDocId));
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
