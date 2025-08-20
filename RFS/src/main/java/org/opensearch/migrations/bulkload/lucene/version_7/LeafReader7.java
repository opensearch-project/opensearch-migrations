package org.opensearch.migrations.bulkload.lucene.version_7;

import java.io.IOException;

import org.opensearch.migrations.bulkload.lucene.BitSetConverter;
import org.opensearch.migrations.bulkload.lucene.LuceneLeafReader;

import lombok.Getter;
import shadow.lucene7.org.apache.lucene.index.FilterCodecReader;
import shadow.lucene7.org.apache.lucene.index.LeafReader;
import shadow.lucene7.org.apache.lucene.index.SegmentReader;
import shadow.lucene7.org.apache.lucene.util.Bits;
import shadow.lucene7.org.apache.lucene.util.FixedBitSet;
import shadow.lucene7.org.apache.lucene.util.SparseFixedBitSet;

public class LeafReader7 implements LuceneLeafReader {

    private final LeafReader wrapped;
    @Getter
    private final BitSetConverter.FixedLengthBitSet liveDocs;

    public LeafReader7(LeafReader wrapped) {
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

    public Document7 document(int luceneDocId) throws IOException {
        return new Document7(wrapped.document(luceneDocId));
    }

    public int maxDoc() {
        return wrapped.maxDoc();
    }

    public String getContextString() {
        return wrapped.getContext().toString();
    }

    private SegmentReader getSegmentReader() {
        var reader = wrapped;
        if (reader instanceof FilterCodecReader) {
            return (SegmentReader) ((FilterCodecReader) reader).getDelegate();
        }
        if (reader instanceof SegmentReader) {
            return (SegmentReader) reader;
        }
        throw new IllegalStateException("Expected to extract SegmentReader but got " +
                reader.getClass() + " from " + wrapped.getClass());
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
