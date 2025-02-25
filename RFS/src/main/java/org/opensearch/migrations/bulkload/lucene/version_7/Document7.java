package org.opensearch.migrations.bulkload.lucene.version_7;

import java.util.List;

import org.opensearch.migrations.bulkload.lucene.MyDocument;
import org.opensearch.migrations.bulkload.lucene.MyField;
import shadow.lucene7.org.apache.lucene.document.Document;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class Document7 implements MyDocument {

    private final Document wrapped;

    public List<Field7> getFields() {
        return wrapped.getFields()
            .stream()
            .map(Field7::new)
            .toList();
    }

}