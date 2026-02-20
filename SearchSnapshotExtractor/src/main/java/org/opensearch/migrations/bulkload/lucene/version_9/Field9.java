package org.opensearch.migrations.bulkload.lucene.version_9;

import java.util.Arrays;

import org.opensearch.migrations.bulkload.common.Uid;
import org.opensearch.migrations.bulkload.lucene.LuceneField;

import lombok.AllArgsConstructor;
import shadow.lucene9.org.apache.lucene.index.IndexableField;

@AllArgsConstructor
public class Field9 implements LuceneField {

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
        if (bytesRef.bytes != null && bytesRef.bytes.length != 0) {
            return bytesRef.utf8ToString();
        }
        return null;
    }
}
