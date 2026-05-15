package org.opensearch.migrations.bulkload.lucene.version_10;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import org.opensearch.migrations.bulkload.lucene.BitSetConverter;
import org.opensearch.migrations.bulkload.lucene.DocValueFieldInfo;
import org.opensearch.migrations.bulkload.lucene.LuceneLeafReader;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import shadow.lucene10.org.apache.lucene.index.BinaryDocValues;
import shadow.lucene10.org.apache.lucene.index.FieldInfo;
import shadow.lucene10.org.apache.lucene.index.FilterCodecReader;
import shadow.lucene10.org.apache.lucene.index.IndexOptions;
import shadow.lucene10.org.apache.lucene.index.LeafReader;
import shadow.lucene10.org.apache.lucene.index.NumericDocValues;
import shadow.lucene10.org.apache.lucene.index.PointValues;
import shadow.lucene10.org.apache.lucene.index.PostingsEnum;
import shadow.lucene10.org.apache.lucene.index.SegmentReader;
import shadow.lucene10.org.apache.lucene.index.SortedNumericDocValues;
import shadow.lucene10.org.apache.lucene.index.SortedSetDocValues;
import shadow.lucene10.org.apache.lucene.index.Terms;
import shadow.lucene10.org.apache.lucene.index.TermsEnum;
import shadow.lucene10.org.apache.lucene.store.ByteArrayDataInput;
import shadow.lucene10.org.apache.lucene.util.Bits;
import shadow.lucene10.org.apache.lucene.util.BytesRef;
import shadow.lucene10.org.apache.lucene.util.FixedBitSet;
import shadow.lucene10.org.apache.lucene.util.SparseFixedBitSet;

@Slf4j
public class LeafReader10 implements LuceneLeafReader {

    private final LeafReader wrapped;
    @Getter
    private final BitSetConverter.FixedLengthBitSet liveDocs;

    public LeafReader10(LeafReader wrapped) {
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

    /**
     * Safely convert BytesRef to String, handling binary data.
     */
    private static String bytesRefToString(BytesRef ref) {
        if (ref == null || ref.length == 0) return null;
        try {
            return ref.utf8ToString();
        } catch (AssertionError | Exception e) {
            byte[] bytes = new byte[ref.length];
            System.arraycopy(ref.bytes, ref.offset, bytes, 0, ref.length);
            return Base64.getEncoder().encodeToString(bytes);
        }
    }

    public Document10 document(int luceneDocId) throws IOException {
        return new Document10(wrapped.storedFields().document(luceneDocId));
    }

    public int maxDoc() {
        return wrapped.maxDoc();
    }

    public String getContextString() {
        return wrapped.getContext().toString();
    }

    private SegmentReader getSegmentReader() {
        var reader = wrapped;
        // FilterCodecReader is created when SoftDeletesDirectoryReaderWrapper encounters a segment with soft deletes
        if (reader instanceof FilterCodecReader) {
            reader = ((FilterCodecReader) reader).getDelegate();
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

    @Override
    public Iterable<DocValueFieldInfo> getDocValueFields() {
        List<DocValueFieldInfo> fields = new ArrayList<>();
        for (FieldInfo fieldInfo : wrapped.getFieldInfos()) {
            DocValueFieldInfo.DocValueType dvType = convertDocValuesType(fieldInfo.getDocValuesType());
            if (dvType != DocValueFieldInfo.DocValueType.NONE) {
                boolean isBoolean = dvType == DocValueFieldInfo.DocValueType.SORTED_NUMERIC 
                    && DocValueFieldInfo.hasOnlyBooleanTerms(getFieldTermsInternal(fieldInfo.name));
                fields.add(new DocValueFieldInfo.Simple(fieldInfo.name, dvType, isBoolean));
            }
        }
        return fields;
    }

    private List<String> getFieldTermsInternal(String fieldName) {
        try {
            Terms terms = wrapped.terms(fieldName);
            if (terms == null) return Collections.emptyList();
            List<String> result = new ArrayList<>();
            TermsEnum termsEnum = terms.iterator();
            BytesRef term;
            while ((term = termsEnum.next()) != null) {
                String termStr = bytesRefToString(term);
                if (termStr != null) {
                    result.add(termStr);
                }
            }
            return result;
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    private static DocValueFieldInfo.DocValueType convertDocValuesType(
            shadow.lucene10.org.apache.lucene.index.DocValuesType luceneType) {
        return switch (luceneType) {
            case NUMERIC -> DocValueFieldInfo.DocValueType.NUMERIC;
            case BINARY -> DocValueFieldInfo.DocValueType.BINARY;
            case SORTED_NUMERIC -> DocValueFieldInfo.DocValueType.SORTED_NUMERIC;
            case SORTED_SET -> DocValueFieldInfo.DocValueType.SORTED_SET;
            case SORTED, NONE -> DocValueFieldInfo.DocValueType.NONE;
        };
    }

    @Override
    public Object getNumericValue(int docId, String fieldName) throws IOException {
        NumericDocValues dv = wrapped.getNumericDocValues(fieldName);
        if (dv != null && dv.advanceExact(docId)) {
            return dv.longValue();
        }
        return null;
    }

    @Override
    public Object getBinaryValue(int docId, String fieldName) throws IOException {
        BinaryDocValues dv = wrapped.getBinaryDocValues(fieldName);
        if (dv != null && dv.advanceExact(docId)) {
            BytesRef value = dv.binaryValue();
            if (value != null && value.length > 0) {
                // Binary doc values use VInt encoding: count + (len + bytes)*
                ByteArrayDataInput in = new ByteArrayDataInput(value.bytes, value.offset, value.length);
                int count = in.readVInt();
                if (count > 0) {
                    int len = in.readVInt();
                    byte[] data = new byte[len];
                    in.readBytes(data, 0, len);
                    return Base64.getEncoder().encodeToString(data);
                }
            }
        }
        return null;
    }

    @Override
    public Object getSortedSetValues(int docId, String fieldName) throws IOException {
        SortedSetDocValues dv = wrapped.getSortedSetDocValues(fieldName);
        if (dv != null && dv.advanceExact(docId)) {
            List<String> values = new ArrayList<>();
            // Lucene 10: NO_MORE_ORDS removed; iterate docValueCount() times.
            int ordCount = dv.docValueCount();
            for (int i = 0; i < ordCount; i++) {
                long ord = dv.nextOrd();
                String val = bytesRefToString(dv.lookupOrd(ord));
                if (val != null) {
                    values.add(val);
                }
            }
            return values.size() == 1 ? values.get(0) : values;
        }
        return null;
    }

    @Override
    public Object getSortedNumericValues(int docId, String fieldName) throws IOException {
        SortedNumericDocValues dv = wrapped.getSortedNumericDocValues(fieldName);
        if (dv != null && dv.advanceExact(docId)) {
            int count = dv.docValueCount();
            if (count == 1) {
                return dv.nextValue();
            }
            List<Long> values = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                values.add(dv.nextValue());
            }
            return values;
        }
        return null;
    }

    @Override
    public List<byte[]> getPointValues(int docId, String fieldName) throws IOException {
        PointValues pointValues = wrapped.getPointValues(fieldName);
        if (pointValues == null) {
            return null;
        }
        
        List<byte[]> result = new ArrayList<>();
        
        pointValues.intersect(new PointValues.IntersectVisitor() {
            @Override
            public void visit(int visitDocId) {
            }
            
            @Override
            public void visit(int visitDocId, byte[] packedValue) {
                if (visitDocId == docId) {
                    result.add(packedValue.clone());
                }
            }
            
            @Override
            public PointValues.Relation compare(byte[] minPackedValue, byte[] maxPackedValue) {
                return PointValues.Relation.CELL_CROSSES_QUERY;
            }
        });
        
        return result.isEmpty() ? null : result;
    }

    @Override
    public String getValueFromTerms(int docId, String fieldName) throws IOException {
        Terms terms = wrapped.terms(fieldName);
        if (terms == null) return null;
        TermsEnum termsEnum = terms.iterator();
        BytesRef term;
        while ((term = termsEnum.next()) != null) {
            PostingsEnum postings = termsEnum.postings(null, PostingsEnum.NONE);
            int doc;
            while ((doc = postings.nextDoc()) != PostingsEnum.NO_MORE_DOCS) {
                if (doc == docId) return term.utf8ToString();
                if (doc > docId) break;
            }
        }
        return null;
    }

    /** See {@link LuceneLeafReader#streamFieldPostings}. */
    @Override
    public void streamFieldPostings(String fieldName,
            org.opensearch.migrations.bulkload.lucene.sidecar.PostingsSink sink) throws IOException {
        Terms terms = wrapped.terms(fieldName);
        if (terms == null) {
            return;
        }
        TermsEnum termsEnum = terms.iterator();
        BytesRef term;
        int[] positions    = new int[16];
        int[] startOffsets = new int[16];
        int[] endOffsets   = new int[16];
        // Only request OFFSETS when the field was actually indexed with them, mirroring the
        // version_9 reader's defensive guard against EverythingEnum touching a missing .pay
        // file when no field in the segment carries offsets/payloads.
        FieldInfo fi = wrapped.getFieldInfos().fieldInfo(fieldName);
        boolean fieldHasOffsets = fi != null
            && fi.getIndexOptions().compareTo(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS) >= 0;
        int postingsFlags = fieldHasOffsets ? PostingsEnum.OFFSETS : PostingsEnum.POSITIONS;
        while ((term = termsEnum.next()) != null) {
            int termId = sink.registerTerm(
                new org.opensearch.migrations.bulkload.lucene.sidecar.BytesRefLike(
                    term.bytes, term.offset, term.length));
            PostingsEnum postings = termsEnum.postings(null, postingsFlags);
            int doc;
            while ((doc = postings.nextDoc()) != PostingsEnum.NO_MORE_DOCS) {
                int freq = postings.freq();
                if (freq > positions.length) {
                    int newLen = Math.max(freq, positions.length * 2);
                    positions    = new int[newLen];
                    startOffsets = new int[newLen];
                    endOffsets   = new int[newLen];
                }
                int n = 0;
                for (int i = 0; i < freq; i++) {
                    int pos = postings.nextPosition();
                    if (pos < 0) continue;
                    positions[n]    = pos;
                    startOffsets[n] = fieldHasOffsets
                        ? postings.startOffset()
                        : org.opensearch.migrations.bulkload.lucene.sidecar.PostingsSink.NO_OFFSET;
                    endOffsets[n]   = fieldHasOffsets
                        ? postings.endOffset()
                        : org.opensearch.migrations.bulkload.lucene.sidecar.PostingsSink.NO_OFFSET;
                    n++;
                }
                if (n > 0) sink.accept(termId, doc, positions, startOffsets, endOffsets, n);
            }
        }
    }
}
