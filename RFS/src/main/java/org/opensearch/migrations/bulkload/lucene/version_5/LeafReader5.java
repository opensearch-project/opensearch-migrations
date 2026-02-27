package org.opensearch.migrations.bulkload.lucene.version_5;

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
import shadow.lucene5.org.apache.lucene.index.BinaryDocValues;
import shadow.lucene5.org.apache.lucene.index.FieldInfo;
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

    public String toString() {
        return wrapped.toString();
    }
}
