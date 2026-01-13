package org.opensearch.migrations.bulkload.lucene.version_6;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.opensearch.migrations.bulkload.lucene.BitSetConverter;
import org.opensearch.migrations.bulkload.lucene.DocValueFieldInfo;
import org.opensearch.migrations.bulkload.lucene.LuceneLeafReader;

import lombok.Getter;
import shadow.lucene6.org.apache.lucene.index.BinaryDocValues;
import shadow.lucene6.org.apache.lucene.index.FieldInfo;
import shadow.lucene6.org.apache.lucene.index.LeafReader;
import shadow.lucene6.org.apache.lucene.index.NumericDocValues;
import shadow.lucene6.org.apache.lucene.index.SegmentReader;
import shadow.lucene6.org.apache.lucene.index.SortedDocValues;
import shadow.lucene6.org.apache.lucene.index.SortedNumericDocValues;
import shadow.lucene6.org.apache.lucene.index.SortedSetDocValues;
import shadow.lucene6.org.apache.lucene.index.Terms;
import shadow.lucene6.org.apache.lucene.index.TermsEnum;
import shadow.lucene6.org.apache.lucene.util.Bits;
import shadow.lucene6.org.apache.lucene.util.BytesRef;
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
                result.add(term.utf8ToString());
            }
            return result;
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    private static DocValueFieldInfo.DocValueType convertDocValuesType(
            shadow.lucene6.org.apache.lucene.index.DocValuesType luceneType) {
        switch (luceneType) {
            case NUMERIC: return DocValueFieldInfo.DocValueType.NUMERIC;
            case BINARY: return DocValueFieldInfo.DocValueType.BINARY;
            case SORTED: return DocValueFieldInfo.DocValueType.SORTED;
            case SORTED_NUMERIC: return DocValueFieldInfo.DocValueType.SORTED_NUMERIC;
            case SORTED_SET: return DocValueFieldInfo.DocValueType.SORTED_SET;
            default: return DocValueFieldInfo.DocValueType.NONE;
        }
    }

    @Override
    public Object getDocValue(int docId, DocValueFieldInfo fieldInfo) throws IOException {
        String fieldName = fieldInfo.name();
        return switch (fieldInfo.docValueType()) {
            case NUMERIC -> getNumericValue(docId, fieldName);
            case SORTED -> getSortedValue(docId, fieldName);
            case SORTED_SET -> getSortedSetValues(docId, fieldName);
            case SORTED_NUMERIC -> getSortedNumericValues(docId, fieldName);
            case BINARY -> getBinaryValue(docId, fieldName);
            case NONE -> null;
        };
    }

    // Lucene 6 uses get(docId) for random access
    private Object getNumericValue(int docId, String fieldName) throws IOException {
        NumericDocValues dv = wrapped.getNumericDocValues(fieldName);
        if (dv != null) {
            return dv.get(docId);
        }
        return null;
    }

    private Object getSortedValue(int docId, String fieldName) throws IOException {
        SortedDocValues dv = wrapped.getSortedDocValues(fieldName);
        if (dv != null) {
            int ord = dv.getOrd(docId);
            if (ord >= 0) {
                return dv.lookupOrd(ord).utf8ToString();
            }
        }
        return null;
    }

    private Object getSortedSetValues(int docId, String fieldName) throws IOException {
        SortedSetDocValues dv = wrapped.getSortedSetDocValues(fieldName);
        if (dv != null) {
            dv.setDocument(docId);
            List<String> values = new ArrayList<>();
            long ord;
            while ((ord = dv.nextOrd()) != SortedSetDocValues.NO_MORE_ORDS) {
                values.add(dv.lookupOrd(ord).utf8ToString());
            }
            if (!values.isEmpty()) {
                return values.size() == 1 ? values.get(0) : values;
            }
        }
        return null;
    }

    private Object getSortedNumericValues(int docId, String fieldName) throws IOException {
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

    private Object getBinaryValue(int docId, String fieldName) throws IOException {
        BinaryDocValues dv = wrapped.getBinaryDocValues(fieldName);
        if (dv != null) {
            BytesRef value = dv.get(docId);
            if (value != null && value.length > 0) {
                return value.utf8ToString();
            }
        }
        return null;
    }

    public String toString() {
        return wrapped.toString();
    }
}
