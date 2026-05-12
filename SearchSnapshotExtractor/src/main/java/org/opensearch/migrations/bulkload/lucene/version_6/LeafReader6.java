package org.opensearch.migrations.bulkload.lucene.version_6;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.opensearch.migrations.bulkload.lucene.BitSetConverter;
import org.opensearch.migrations.bulkload.lucene.DocValueFieldInfo;
import org.opensearch.migrations.bulkload.lucene.LuceneLeafReader;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import shadow.lucene6.org.apache.lucene.index.BinaryDocValues;
import shadow.lucene6.org.apache.lucene.index.FieldInfo;
import shadow.lucene6.org.apache.lucene.index.IndexOptions;
import shadow.lucene6.org.apache.lucene.index.LeafReader;
import shadow.lucene6.org.apache.lucene.index.NumericDocValues;
import shadow.lucene6.org.apache.lucene.index.PointValues;
import shadow.lucene6.org.apache.lucene.index.PostingsEnum;
import shadow.lucene6.org.apache.lucene.index.SegmentReader;
import shadow.lucene6.org.apache.lucene.index.SortedDocValues;
import shadow.lucene6.org.apache.lucene.index.SortedNumericDocValues;
import shadow.lucene6.org.apache.lucene.index.SortedSetDocValues;
import shadow.lucene6.org.apache.lucene.index.Terms;
import shadow.lucene6.org.apache.lucene.index.TermsEnum;
import shadow.lucene6.org.apache.lucene.store.ByteArrayDataInput;
import shadow.lucene6.org.apache.lucene.util.Bits;
import shadow.lucene6.org.apache.lucene.util.BytesRef;
import shadow.lucene6.org.apache.lucene.util.FixedBitSet;
import shadow.lucene6.org.apache.lucene.util.SparseFixedBitSet;

@Slf4j
public class LeafReader6 implements LuceneLeafReader {

    private final LeafReader wrapped;
    @Getter
    private final BitSetConverter.FixedLengthBitSet liveDocs;
    private volatile List<DocValueFieldInfo> docValueFieldsCache;

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

    @Override
    public List<DocValueFieldInfo> getDocValueFields() {
        List<DocValueFieldInfo> cached = docValueFieldsCache;
        if (cached != null) return cached;
        List<DocValueFieldInfo> fields = new ArrayList<>();
        for (FieldInfo fieldInfo : wrapped.getFieldInfos()) {
            DocValueFieldInfo.DocValueType dvType = convertDocValuesType(fieldInfo.getDocValuesType());
            log.atDebug().setMessage("Field {} has docValuesType {} -> {}").addArgument(fieldInfo.name).addArgument(fieldInfo.getDocValuesType()).addArgument(dvType).log();
            if (dvType != DocValueFieldInfo.DocValueType.NONE) {
                boolean isBoolean = dvType == DocValueFieldInfo.DocValueType.SORTED_NUMERIC
                    && fieldHasOnlyBooleanTerms(fieldInfo.name);
                fields.add(new DocValueFieldInfo.Simple(fieldInfo.name, dvType, isBoolean));
            }
        }
        docValueFieldsCache = fields;
        return fields;
    }

    /** Short-circuiting T/F-only check: bails on the first non-boolean term without materializing the list. */
    private boolean fieldHasOnlyBooleanTerms(String fieldName) {
        try {
            Terms terms = wrapped.terms(fieldName);
            if (terms == null) return false;
            TermsEnum termsEnum = terms.iterator();
            BytesRef term;
            boolean hasTerms = false;
            while ((term = termsEnum.next()) != null) {
                hasTerms = true;
                if (term.length != 1
                        || (term.bytes[term.offset] != DocValueFieldInfo.BOOLEAN_TERM_T
                            && term.bytes[term.offset] != DocValueFieldInfo.BOOLEAN_TERM_F)) {
                    return false;
                }
            }
            return hasTerms;
        } catch (IOException e) {
            return false;
        }
    }

    private static DocValueFieldInfo.DocValueType convertDocValuesType(
            shadow.lucene6.org.apache.lucene.index.DocValuesType luceneType) {
        return switch (luceneType) {
            case NUMERIC -> DocValueFieldInfo.DocValueType.NUMERIC;
            case BINARY -> DocValueFieldInfo.DocValueType.BINARY;
            // SORTED: single-valued keyword/string field — Lucene 6 stores it as SortedDocValues.
            // Surfacing it lets keyword reverse-derivation (e.g. copy_to keyword target) hit the
            // doc_values tier and return the full original term, instead of falling through to
            // the points/terms tokenization path which would lossily split "joe smith" into "joe".
            case SORTED -> DocValueFieldInfo.DocValueType.SORTED;
            case SORTED_NUMERIC -> DocValueFieldInfo.DocValueType.SORTED_NUMERIC;
            case SORTED_SET -> DocValueFieldInfo.DocValueType.SORTED_SET;
            case NONE -> DocValueFieldInfo.DocValueType.NONE;
        };
    }

    @Override
    public Object getSortedValue(int docId, String fieldName) throws IOException {
        SortedDocValues dv = wrapped.getSortedDocValues(fieldName);
        if (dv != null) {
            // Lucene 6 SortedDocValues uses random-access get(docId); ord -1 means no value.
            int ord = dv.getOrd(docId);
            if (ord >= 0) {
                return bytesRefToString(dv.lookupOrd(ord));
            }
        }
        return null;
    }

    @Override
    public Object getBinaryValue(int docId, String fieldName) throws IOException {
        BinaryDocValues dv = wrapped.getBinaryDocValues(fieldName);
        if (dv != null) {
            BytesRef value = dv.get(docId);
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

    // Lucene 6 uses get(docId) for random access
    @Override
    public Object getNumericValue(int docId, String fieldName) throws IOException {
        NumericDocValues dv = wrapped.getNumericDocValues(fieldName);
        if (dv != null) {
            return dv.get(docId);
        }
        return null;
    }

    @Override
    public Object getSortedSetValues(int docId, String fieldName) throws IOException {
        SortedSetDocValues dv = wrapped.getSortedSetDocValues(fieldName);
        if (dv != null) {
            dv.setDocument(docId);
            List<String> values = new ArrayList<>();
            long ord;
            while ((ord = dv.nextOrd()) != SortedSetDocValues.NO_MORE_ORDS) {
                String val = bytesRefToString(dv.lookupOrd(ord));
                if (val != null) {
                    values.add(val);
                }
            }
            if (!values.isEmpty()) {
                return values.size() == 1 ? values.get(0) : values;
            }
        }
        return null;
    }

    @Override
    public Object getSortedNumericValues(int docId, String fieldName) throws IOException {
        SortedNumericDocValues dv = wrapped.getSortedNumericDocValues(fieldName);
        if (dv != null) {
            dv.setDocument(docId);
            int count = dv.count();
            if (count > 0) {
                if (count == 1) {
                    return dv.valueAt(0);
                }
                List<Long> values = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    values.add(dv.valueAt(i));
                }
                return values;
            }
        }
        return null;
    }

    public String toString() {
        return wrapped.toString();
    }

    @Override
    public List<byte[]> getPointValues(int docId, String fieldName) throws IOException {
        PointValues pointValues = wrapped.getPointValues();
        if (pointValues == null) {
            return null;
        }
        
        // Check if field has points
        int numDims;
        try {
            numDims = pointValues.getNumDimensions(fieldName);
        } catch (Exception e) {
            return null;
        }
        if (numDims == 0) {
            return null;
        }
        
        List<byte[]> result = new ArrayList<>();
        
        pointValues.intersect(fieldName, new PointValues.IntersectVisitor() {
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
        // Requesting OFFSETS on a POSITIONS-only field in Lucene 6 routes to an
        // EverythingEnum that reads the .pay file — but if no field in the segment
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

}
