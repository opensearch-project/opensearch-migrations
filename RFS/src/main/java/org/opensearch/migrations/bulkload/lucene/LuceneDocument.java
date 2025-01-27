package org.opensearch.migrations.bulkload.lucene;

import java.util.List;

public interface LuceneDocument {
    List<LuceneDocField> getFields();
}