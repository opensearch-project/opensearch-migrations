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
        
        // Normal case for ES 6.x / ES 7.x → _id stored as binary
        if (binaryValue != null && binaryValue.bytes != null) {
            return Uid.decodeId(binaryValue.bytes);
        }

        // Fallback for upgraded indices:
        // ES 5.x → ES 6.x with mapping.single_type = true
        // _id is stored as a stored string field, not binary
        String stringValue = wrapped.stringValue();
        if (stringValue != null) {
            // Normalize "type#id"
            int hash = stringValue.indexOf('#');
            return (hash >= 0) ? stringValue.substring(hash + 1) : stringValue;
        }

        // Expected behavior with no id : warn about skipping document
        return null;
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
