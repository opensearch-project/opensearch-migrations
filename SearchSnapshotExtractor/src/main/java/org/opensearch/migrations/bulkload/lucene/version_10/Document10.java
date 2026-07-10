package org.opensearch.migrations.bulkload.lucene.version_10;

import java.util.List;

import org.opensearch.migrations.bulkload.lucene.LuceneDocument;

import lombok.AllArgsConstructor;
import shadow.lucene10.org.apache.lucene.document.Document;

@AllArgsConstructor
public class Document10 implements LuceneDocument {

    private final Document wrapped;

    public List<Field10> getFields() {
        return wrapped.getFields()
            .stream()
            .map(Field10::new)
            .toList();
    }
}
