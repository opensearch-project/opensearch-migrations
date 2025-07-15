package org.opensearch.migrations.bulkload.lucene.version_5;

import java.util.List;

import org.opensearch.migrations.bulkload.lucene.LuceneDocument;

import lombok.AllArgsConstructor;
import shadow.lucene5.org.apache.lucene.document.Document;

@AllArgsConstructor
public class Document5 implements LuceneDocument {

    private final Document wrapped;

    public List<Field5> getFields() {
        return wrapped.getFields()
                .stream()
                .map(Field5::new)
                .toList();
    }
}
