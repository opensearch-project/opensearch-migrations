package org.opensearch.migrations.bulkload.lucene.version_7;

import org.opensearch.migrations.bulkload.common.Uid;
import org.opensearch.migrations.bulkload.lucene.LuceneField;

import lombok.AllArgsConstructor;
import shadow.lucene7.org.apache.lucene.index.IndexableField;

@AllArgsConstructor
public class Field7 implements LuceneField {

    private final IndexableField wrapped;

    @Override
    public String name() {
        return wrapped.name();
    }

    @Override
    public String asUid() {
        var binaryValue = wrapped.binaryValue();
        
        // For ES 6.x / ES 7.x case: _id stored as binary
        if (binaryValue != null && binaryValue.bytes != null) {
            return Uid.decodeId(binaryValue.bytes);
        }

        // Fallback for the ES 5.x + single_type case where _id was stored as a string.
        return wrapped.stringValue();
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
