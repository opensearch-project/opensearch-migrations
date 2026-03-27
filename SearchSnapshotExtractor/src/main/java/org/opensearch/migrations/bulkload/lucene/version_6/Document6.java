package org.opensearch.migrations.bulkload.lucene.version_6;

import java.util.List;

import org.opensearch.migrations.bulkload.lucene.LuceneDocument;

import lombok.AllArgsConstructor;
import shadow.lucene6.org.apache.lucene.document.Document;

@AllArgsConstructor
public class Document6 implements LuceneDocument {

    private final Document wrapped;

    public List<Field6> getFields() {
        return wrapped.getFields()
            .stream()
            .map(Field6::new)
            .toList();
    }
}
