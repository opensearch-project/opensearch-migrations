package org.opensearch.migrations.bulkload.lucene.version_7;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.bulkload.lucene.BitSetConverter;
import org.opensearch.migrations.bulkload.lucene.DocValueFieldInfo;
import org.opensearch.migrations.bulkload.lucene.GenericStreamingFieldPostings;
import org.opensearch.migrations.bulkload.lucene.LuceneLeafReader;

import lombok.Getter;
import shadow.lucene7.org.apache.lucene.index.BinaryDocValues;
import shadow.lucene7.org.apache.lucene.index.FieldInfo;
import shadow.lucene7.org.apache.lucene.index.FilterCodecReader;
import shadow.lucene7.org.apache.lucene.index.IndexOptions;
import shadow.lucene7.org.apache.lucene.index.LeafReader;
import shadow.lucene7.org.apache.lucene.index.NumericDocValues;
import shadow.lucene7.org.apache.lucene.index.PointValues;
import shadow.lucene7.org.apache.lucene.index.PostingsEnum;
import shadow.lucene7.org.apache.lucene.index.SegmentReader;
import shadow.lucene7.org.apache.lucene.index.SortedDocValues;
import shadow.lucene7.org.apache.lucene.index.SortedNumericDocValues;
import shadow.lucene7.org.apache.lucene.index.SortedSetDocValues;
import shadow.lucene7.org.apache.lucene.index.Terms;
import shadow.lucene7.org.apache.lucene.index.TermsEnum;
import shadow.lucene7.org.apache.lucene.store.ByteArrayDataInput;
import shadow.lucene7.org.apache.lucene.util.Bits;
import shadow.lucene7.org.apache.lucene.util.BytesRef;
import shadow.lucene7.org.apache.lucene.util.FixedBitSet;
import shadow.lucene7.org.apache.lucene.util.SparseFixedBitSet;

public class LeafReader7 implements LuceneLeafReader {

    private final LeafReader wrapped;
    @Getter
    private final BitSetConverter.FixedLengthBitSet liveDocs;

    private Map<String, NumericDocValues> cachedNumericDv;
    private Map<String, BinaryDocValues> cachedBinaryDv;
    private Map<String, SortedDocValues> cachedSortedDv;
    private Map<String, SortedSetDocValues> cachedSortedSetDv;
    private Map<String, SortedNumericDocValues> cachedSortedNumericDv;

    public LeafReader7(LeafReader wrapped) {
        this.wrapped = wrapped;
        this.liveDocs = convertLiveDocs(wrapped.getLiveDocs());
    }

    @Override
    public LuceneLeafReader newView() {
        return new LeafReader7(wrapped);
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

    private volatile List<DocValueFieldInfo> cachedDocValueFields;

    @Override
    public Iterable<DocValueFieldInfo> getDocValueFields() {
        List<DocValueFieldInfo> cached = cachedDocValueFields;
        if (cached != null) return cached;
        List<DocValueFieldInfo> fields = new ArrayList<>();
        for (FieldInfo fieldInfo : wrapped.getFieldInfos()) {
            DocValueFieldInfo.DocValueType dvType = convertDocValuesType(fieldInfo.getDocValuesType());
            if (dvType != DocValueFieldInfo.DocValueType.NONE) {
                boolean isBoolean = dvType == DocValueFieldInfo.DocValueType.SORTED_NUMERIC
                    && DocValueFieldInfo.hasOnlyBooleanTerms(getFieldTermsInternal(fieldInfo.name));
                fields.add(new DocValueFieldInfo.Simple(fieldInfo.name, dvType, isBoolean));
            }
        }
        cachedDocValueFields = Collections.unmodifiableList(fields);
        return cachedDocValueFields;
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
            shadow.lucene7.org.apache.lucene.index.DocValuesType luceneType) {
        return switch (luceneType) {
            case NUMERIC -> DocValueFieldInfo.DocValueType.NUMERIC;
            case BINARY -> DocValueFieldInfo.DocValueType.BINARY;
            case SORTED -> DocValueFieldInfo.DocValueType.SORTED;
            case SORTED_NUMERIC -> DocValueFieldInfo.DocValueType.SORTED_NUMERIC;
            case SORTED_SET -> DocValueFieldInfo.DocValueType.SORTED_SET;
            case NONE -> DocValueFieldInfo.DocValueType.NONE;
        };
    }

    @Override
    public void initDocValueIterators(Iterable<DocValueFieldInfo> fields) throws IOException {
        cachedNumericDv = new HashMap<>();
        cachedBinaryDv = new HashMap<>();
        cachedSortedDv = new HashMap<>();
        cachedSortedSetDv = new HashMap<>();
        cachedSortedNumericDv = new HashMap<>();
        for (DocValueFieldInfo fi : fields) {
            String name = fi.name();
            switch (fi.docValueType()) {
                case NUMERIC -> {
                    NumericDocValues dv = wrapped.getNumericDocValues(name);
                    if (dv != null) cachedNumericDv.put(name, dv);
                }
                case BINARY -> {
                    BinaryDocValues dv = wrapped.getBinaryDocValues(name);
                    if (dv != null) cachedBinaryDv.put(name, dv);
                }
                case SORTED -> {
                    SortedDocValues dv = wrapped.getSortedDocValues(name);
                    if (dv != null) cachedSortedDv.put(name, dv);
                }
                case SORTED_SET -> {
                    SortedSetDocValues dv = wrapped.getSortedSetDocValues(name);
                    if (dv != null) cachedSortedSetDv.put(name, dv);
                }
                case SORTED_NUMERIC -> {
                    SortedNumericDocValues dv = wrapped.getSortedNumericDocValues(name);
                    if (dv != null) cachedSortedNumericDv.put(name, dv);
                }
                default -> {}
            }
        }
    }

    @Override
    public Object getNumericValue(int docId, String fieldName) throws IOException {
        NumericDocValues dv = (cachedNumericDv != null)
            ? cachedNumericDv.get(fieldName)
            : wrapped.getNumericDocValues(fieldName);
        if (dv != null && dv.advanceExact(docId)) {
            return dv.longValue();
        }
        return null;
    }

    @Override
    public Object getSortedValue(int docId, String fieldName) throws IOException {
        SortedDocValues dv = (cachedSortedDv != null)
            ? cachedSortedDv.get(fieldName)
            : wrapped.getSortedDocValues(fieldName);
        if (dv != null && dv.advanceExact(docId)) {
            return bytesRefToString(dv.lookupOrd(dv.ordValue()));
        }
        return null;
    }

    @Override
    public Object getSortedSetValues(int docId, String fieldName) throws IOException {
        SortedSetDocValues dv = (cachedSortedSetDv != null)
            ? cachedSortedSetDv.get(fieldName)
            : wrapped.getSortedSetDocValues(fieldName);
        if (dv != null && dv.advanceExact(docId)) {
            List<String> values = new ArrayList<>();
            long ord;
            while ((ord = dv.nextOrd()) != SortedSetDocValues.NO_MORE_ORDS) {
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
        SortedNumericDocValues dv = (cachedSortedNumericDv != null)
            ? cachedSortedNumericDv.get(fieldName)
            : wrapped.getSortedNumericDocValues(fieldName);
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
    public Object getBinaryValue(int docId, String fieldName) throws IOException {
        BinaryDocValues dv = (cachedBinaryDv != null)
            ? cachedBinaryDv.get(fieldName)
            : wrapped.getBinaryDocValues(fieldName);
        if (dv != null && dv.advanceExact(docId)) {
            BytesRef value = dv.binaryValue();
            if (value != null && value.length > 0) {
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

    public String toString() {
        return wrapped.toString();
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
        if (terms == null) return;
        // If positions weren't indexed (e.g. keyword/"not_analyzed" string, or text with
        // index_options stripped below positions) there's nothing useful to stream — drop out
        // so the caller falls through to the single-term path. Requesting POSITIONS on a
        // postings list that doesn't carry them is undefined and can yield bogus sentinels.
        if (!terms.hasPositions()) return;
        TermsEnum termsEnum = terms.iterator();
        BytesRef term;
        int[] positions    = new int[16];
        int[] startOffsets = new int[16];
        int[] endOffsets   = new int[16];
        // Only request OFFSETS when the field was actually indexed with them.
        // Requesting OFFSETS on a POSITIONS-only field in Lucene 7 routes to
        // EverythingEnum which reads the .pay file — but if no field in the segment
        // has offsets/payloads, that file is never written and payIn == null, causing NPE.
        FieldInfo fi = wrapped.getFieldInfos().fieldInfo(fieldName);
        boolean fieldHasOffsets = fi != null
            && fi.getIndexOptions().compareTo(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS) >= 0;
        int postingsFlags = fieldHasOffsets ? PostingsEnum.OFFSETS : PostingsEnum.POSITIONS;
        while ((term = termsEnum.next()) != null) {
            int termId = sink.registerTerm(
                new org.opensearch.migrations.bulkload.lucene.sidecar.BytesRefLike(
                    term.bytes, term.offset, term.length));
            // OFFSETS is a superset of POSITIONS; also returns character start/end offsets when
            // the field was indexed with index_options:offsets, or -1 otherwise.
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

    @Override
    public org.opensearch.migrations.bulkload.lucene.StreamingFieldPostings openStreamingFieldPostings(
            String fieldName) throws IOException {
        Terms terms = wrapped.terms(fieldName);
        if (terms == null || !terms.hasPositions()) {
            return null;
        }
        FieldInfo fi = wrapped.getFieldInfos().fieldInfo(fieldName);
        boolean fieldHasOffsets = fi != null
            && fi.getIndexOptions().compareTo(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS) >= 0;
        int postingsFlags = fieldHasOffsets ? PostingsEnum.OFFSETS : PostingsEnum.POSITIONS;

        ArrayList<GenericStreamingFieldPostings.TermPostings> built = new ArrayList<>();
        TermsEnum te = terms.iterator();
        BytesRef term;
        while ((term = te.next()) != null) {
            String termStr = term.utf8ToString();
            PostingsEnum postings = te.postings(null, postingsFlags);
            int firstDoc = postings.nextDoc();
            if (firstDoc == PostingsEnum.NO_MORE_DOCS) continue;
            final PostingsEnum pe = postings;
            GenericStreamingFieldPostings.PostingsCursor cursor =
                new GenericStreamingFieldPostings.PostingsCursor() {
                    @Override public int nextDoc() throws IOException { return pe.nextDoc(); }
                    @Override public int advance(int target) throws IOException { return pe.advance(target); }
                    @Override public int freq() throws IOException { return pe.freq(); }
                    @Override public int nextPosition() throws IOException { return pe.nextPosition(); }
                    @Override public int startOffset() throws IOException { return pe.startOffset(); }
                    @Override public int endOffset() throws IOException { return pe.endOffset(); }
                };
            built.add(new GenericStreamingFieldPostings.TermPostings(termStr, cursor, firstDoc));
        }
        if (built.isEmpty()) return null;
        return GenericStreamingFieldPostings.build(built, fieldHasOffsets);
    }
}
