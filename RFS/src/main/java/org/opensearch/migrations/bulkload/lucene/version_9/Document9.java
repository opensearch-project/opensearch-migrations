package org.opensearch.migrations.bulkload.lucene.version_9;

import java.util.List;

import org.opensearch.migrations.bulkload.lucene.LuceneDocument;

import lombok.AllArgsConstructor;
import shadow.lucene9.org.apache.lucene.document.Document;

@AllArgsConstructor
public class Document9 implements LuceneDocument {

    private final Document wrapped;

    public List<Field9> getFields() {
        return wrapped.getFields()
            .stream()
            .map(Field9::new)
            .toList();
    }
}
