package org.opensearch.migrations.bulkload.lucene.version_5;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.bulkload.lucene.BitSetConverter;
import org.opensearch.migrations.bulkload.lucene.DocValueFieldInfo;
import org.opensearch.migrations.bulkload.lucene.LuceneLeafReader;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import shadow.lucene5.org.apache.lucene.index.BinaryDocValues;
import shadow.lucene5.org.apache.lucene.index.FieldInfo;
import shadow.lucene5.org.apache.lucene.index.IndexOptions;
import shadow.lucene5.org.apache.lucene.index.LeafReader;
import shadow.lucene5.org.apache.lucene.index.NumericDocValues;
import shadow.lucene5.org.apache.lucene.index.PostingsEnum;
import shadow.lucene5.org.apache.lucene.index.SegmentReader;
import shadow.lucene5.org.apache.lucene.index.SortedNumericDocValues;
import shadow.lucene5.org.apache.lucene.index.SortedSetDocValues;
import shadow.lucene5.org.apache.lucene.index.Terms;
import shadow.lucene5.org.apache.lucene.index.TermsEnum;
import shadow.lucene5.org.apache.lucene.store.ByteArrayDataInput;
import shadow.lucene5.org.apache.lucene.util.Bits;
import shadow.lucene5.org.apache.lucene.util.BytesRef;
import shadow.lucene5.org.apache.lucene.util.FixedBitSet;
import shadow.lucene5.org.apache.lucene.util.NumericUtils;
import shadow.lucene5.org.apache.lucene.util.SparseFixedBitSet;

@Slf4j
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

    /**
     * Safely convert BytesRef to String, handling binary data.
     * For valid UTF-8, returns the string. For binary data, returns base64 encoding.
     */
    private static String bytesRefToString(BytesRef ref) {
        if (ref == null || ref.length == 0) return null;
        try {
            return ref.utf8ToString();
        } catch (AssertionError | Exception e) {
            // Binary data - return base64 encoding
            byte[] bytes = new byte[ref.length];
            System.arraycopy(ref.bytes, ref.offset, bytes, 0, ref.length);
            return Base64.getEncoder().encodeToString(bytes);
        }
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
            shadow.lucene5.org.apache.lucene.index.DocValuesType luceneType) {
        return switch (luceneType) {
            case NUMERIC -> DocValueFieldInfo.DocValueType.NUMERIC;
            case BINARY -> DocValueFieldInfo.DocValueType.BINARY;
            case SORTED_NUMERIC -> DocValueFieldInfo.DocValueType.SORTED_NUMERIC;
            case SORTED_SET -> DocValueFieldInfo.DocValueType.SORTED_SET;
            case SORTED, NONE -> DocValueFieldInfo.DocValueType.NONE;
        };
    }

    @Override
    public Object getBinaryValue(int docId, String fieldName) throws IOException {
        BinaryDocValues dv = wrapped.getBinaryDocValues(fieldName);
        if (dv != null) {
            BytesRef value = dv.get(docId);
            if (value != null && value.length > 0) {
                try {
                    ByteArrayDataInput in = new ByteArrayDataInput(value.bytes, value.offset, value.length);
                    int count = in.readVInt();
                    if (count > 0) {
                        int len = in.readVInt();
                        byte[] data = new byte[len];
                        in.readBytes(data, 0, len);
                        return Base64.getEncoder().encodeToString(data);
                    }
                } catch (ArrayIndexOutOfBoundsException e) {
                    // Different binary encoding (e.g., geo_point in ES 1.x) - return raw bytes
                    byte[] raw = new byte[value.length];
                    System.arraycopy(value.bytes, value.offset, raw, 0, value.length);
                    return Base64.getEncoder().encodeToString(raw);
                }
            }
        }
        return null;
    }

    // Lucene 5 uses get(docId) for random access
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

    @Override
    public String getValueFromTerms(int docId, String fieldName) throws IOException {
        Terms terms = wrapped.terms(fieldName);
        if (terms == null) {
            log.debug("[Terms] Field {} has no terms index", fieldName);
            return null;
        }

        log.debug("[Terms] Scanning terms for field {} docId {} (approx {} unique terms)", 
            fieldName, docId, terms.size());
        
        TermsEnum termsEnum = terms.iterator();
        BytesRef term;
        int termCount = 0;
        int totalPostingsScanned = 0;
        
        while ((term = termsEnum.next()) != null) {
            termCount++;
            String termStr = term.utf8ToString();
            PostingsEnum postings = termsEnum.postings(null, PostingsEnum.NONE);
            int doc;
            int postingsForThisTerm = 0;
            while ((doc = postings.nextDoc()) != PostingsEnum.NO_MORE_DOCS) {
                postingsForThisTerm++;
                totalPostingsScanned++;
                if (doc == docId) {
                    log.debug("[Terms] Found value '{}' for field {} docId {} after scanning {} terms, {} total postings", 
                        termStr, fieldName, docId, termCount, totalPostingsScanned);
                    return termStr;
                }
                if (doc > docId) {
                    break; // Postings are sorted by docId
                }
            }
            log.trace("[Terms] Term '{}' scanned {} postings, not found", termStr, postingsForThisTerm);
        }
        
        log.debug("[Terms] No value found for field {} docId {} after scanning {} terms, {} total postings", 
            fieldName, docId, termCount, totalPostingsScanned);
        return null;
    }

    /**
     * See {@link LuceneLeafReader#streamFieldPostings}. Single-pass walk of the terms
     * dictionary for {@code fieldName}, streaming {@code (termId, docId, positions[])}
     * callbacks to the sink. Used by {@link SegmentTermIndex} to reconstruct
     * analyzed-text fields when neither stored fields nor doc_values are available.
     */
    @Override
    public void streamFieldPostings(String fieldName,
            org.opensearch.migrations.bulkload.lucene.sidecar.PostingsSink sink) throws IOException {
        Terms terms = wrapped.terms(fieldName);
        if (terms == null) return;
        TermsEnum termsEnum = terms.iterator();
        BytesRef term;
        int[] positions    = new int[16];
        int[] startOffsets = new int[16];
        int[] endOffsets   = new int[16];
        // Only request OFFSETS when the field was actually indexed with them.
        // Requesting OFFSETS on a POSITIONS-only field causes Lucene 5's EverythingEnum
        // to try reading the .pay file — but if no field in the segment has
        // offsets/payloads, that file is never written and payIn == null, causing NPE.
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

    /**
     * Single-pass walk of the terms dictionary for a trie-encoded numeric {@code fieldName}.
     * Harvests only shift==0 terms (fully-precise values) and decodes via
     * {@link NumericUtils#prefixCodedToLong} / {@link NumericUtils#prefixCodedToInt}.
     *
     * Terms at higher shift levels are range-query prefix terms that exist alongside every
     * value for efficient range scans — they are skipped here because the shift==0 term for
     * a value is posted against exactly the same docs.
     *
     * Byte-length discriminates int-coded (6 bytes at shift=0) vs long-coded (11 bytes at
     * shift=0) terms. ES 1.x IP fields use long-coded terms even though the value is 32-bit.
     *
     * Returns {@code docId -> decoded Long} (first value wins when multi-valued; multi-valued
     * numeric fields are extremely rare in practice and the reconstructed JSON would need
     * array support at a higher layer to benefit anyway).
     */
    @Override
    public Map<Integer, Long> buildNumericTermIndex(String fieldName) throws IOException {
        Terms terms = wrapped.terms(fieldName);
        if (terms == null) return Collections.emptyMap();
        Map<Integer, Long> result = new HashMap<>();
        TermsEnum termsEnum = terms.iterator();
        BytesRef term;
        while ((term = termsEnum.next()) != null) {
            Long decoded = decodeShiftZeroTerm(term);
            if (decoded == null) continue;
            PostingsEnum postings = termsEnum.postings(null, PostingsEnum.NONE);
            int doc;
            while ((doc = postings.nextDoc()) != PostingsEnum.NO_MORE_DOCS) {
                result.putIfAbsent(doc, decoded);
            }
        }
        return result;
    }

    /**
     * Returns the decoded numeric value for a term if it is a shift==0 prefix-coded long/int,
     * else null. Lucene 4/5 encodes the first byte as {@code SHIFT_START_LONG + shift} (0x20)
     * for long-indexed fields and {@code SHIFT_START_INT + shift} (0x60) for int-indexed
     * fields, so the header byte uniquely identifies both the encoding family and the shift.
     * Only shift==0 terms are fully-precise; higher-shift range terms are skipped since the
     * shift==0 term covers the same docs at full precision.
     */
    private static Long decodeShiftZeroTerm(BytesRef term) {
        if (term.length == 0) return null;
        byte header = term.bytes[term.offset];
        if (header == NumericUtils.SHIFT_START_LONG && term.length == NumericUtils.BUF_SIZE_LONG) {
            return NumericUtils.prefixCodedToLong(term);
        }
        if (header == NumericUtils.SHIFT_START_INT && term.length == NumericUtils.BUF_SIZE_INT) {
            return (long) NumericUtils.prefixCodedToInt(term);
        }
        return null;
    }

    public String toString() {
        return wrapped.toString();
    }
}
