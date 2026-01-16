package org.opensearch.migrations.bulkload.lucene.version_6;

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
import shadow.lucene6.org.apache.lucene.index.BinaryDocValues;
import shadow.lucene6.org.apache.lucene.index.FieldInfo;
import shadow.lucene6.org.apache.lucene.index.LeafReader;
import shadow.lucene6.org.apache.lucene.index.NumericDocValues;
import shadow.lucene6.org.apache.lucene.index.PointValues;
import shadow.lucene6.org.apache.lucene.index.PostingsEnum;
import shadow.lucene6.org.apache.lucene.index.SegmentReader;
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
    public Iterable<DocValueFieldInfo> getDocValueFields() {
        List<DocValueFieldInfo> fields = new ArrayList<>();
        for (FieldInfo fieldInfo : wrapped.getFieldInfos()) {
            DocValueFieldInfo.DocValueType dvType = convertDocValuesType(fieldInfo.getDocValuesType());
            log.atDebug().setMessage("Field {} has docValuesType {} -> {}").addArgument(fieldInfo.name).addArgument(fieldInfo.getDocValuesType()).addArgument(dvType).log();
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
            shadow.lucene6.org.apache.lucene.index.DocValuesType luceneType) {
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
}
