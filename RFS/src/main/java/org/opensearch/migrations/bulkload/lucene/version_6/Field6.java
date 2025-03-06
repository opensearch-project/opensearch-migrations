package org.opensearch.migrations.bulkload.lucene.version_6;

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
        return Uid.decodeId(wrapped.binaryValue().bytes);
    }

    @Override
    public String stringValue() {
        return wrapped.stringValue();
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
