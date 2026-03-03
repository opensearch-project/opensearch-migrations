package org.opensearch.migrations.bulkload.lucene.version_5;

import org.opensearch.migrations.bulkload.common.Uid;
import org.opensearch.migrations.bulkload.lucene.LuceneField;

import lombok.AllArgsConstructor;
import shadow.lucene5.org.apache.lucene.index.IndexableField;

@AllArgsConstructor
public class Field5 implements LuceneField {

    private final IndexableField wrapped;

    @Override
    public String name() {
        return wrapped.name();
    }

    @Override
    public String asUid() {
        return Uid.decodeId(wrapped.binaryValue().bytes);
    }

    @Override
    public String stringValue() {
        return wrapped.stringValue();
    }

    @Override
    public String utf8ToStringValue() {
        var bytesRef = wrapped.binaryValue();
        if (bytesRef != null && bytesRef.bytes != null && bytesRef.bytes.length != 0) {
            return bytesRef.utf8ToString();
        }
        return null;
    }

    @Override
    public byte[] binaryValue() {
        var bytesRef = wrapped.binaryValue();
        if (bytesRef != null && bytesRef.bytes != null && bytesRef.length > 0) {
            byte[] result = new byte[bytesRef.length];
            System.arraycopy(bytesRef.bytes, bytesRef.offset, result, 0, bytesRef.length);
            return result;
        }
        return null;
    }
}
