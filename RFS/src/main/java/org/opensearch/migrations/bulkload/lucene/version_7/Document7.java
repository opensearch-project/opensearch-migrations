package org.opensearch.migrations.bulkload.lucene.version_7;

import java.util.List;

import org.opensearch.migrations.bulkload.lucene.LuceneDocument;

import lombok.AllArgsConstructor;
import shadow.lucene7.org.apache.lucene.document.Document;

@AllArgsConstructor
public class Document7 implements LuceneDocument {

    private final Document wrapped;

    public List<Field7> getFields() {
        var foo = wrapped.getFields()
            .stream()
            .map(Field7::new)
            .toList();
        return foo;
    }
}
