package org.opensearch.migrations.bulkload.lucene.version_6;

import java.util.Arrays;

import org.opensearch.migrations.bulkload.common.Uid;
import org.opensearch.migrations.bulkload.lucene.LuceneField;

import lombok.AllArgsConstructor;
import shadow.lucene6.org.apache.lucene.index.IndexableField;

@AllArgsConstructor
public class Field6 implements LuceneField {

    private final IndexableField wrapped;

    @Override
    public String name() {
        return wrapped.name();
    }

    @Override
    public String asUid() {
        var binaryValue = wrapped.binaryValue();
        if (binaryValue != null && binaryValue.bytes != null) {
            return Uid.decodeId(binaryValue.bytes);
        }
        // ES 5.x indices with index.mapping.single_type=true
        // In this configuration, _id is stored as a string instead of using _uid
        return wrapped.stringValue();
    }

    @Override
    public String stringValue() {
        return wrapped.stringValue();
    }

    @Override
    public byte[] utf8Value() {
        var bytesRef = wrapped.binaryValue();
        if (bytesRef != null && bytesRef.bytes != null && bytesRef.bytes.length != 0) {
            return Arrays.copyOfRange(bytesRef.bytes, bytesRef.offset, bytesRef.offset + bytesRef.length);
        }
        return null;
    }

    @Override
    public String utf8ToStringValue() {
        var bytesRef = wrapped.binaryValue();
        if (bytesRef != null && bytesRef.bytes != null && bytesRef.bytes.length != 0) {
            return bytesRef.utf8ToString();
        }
        return null;
    }
}
