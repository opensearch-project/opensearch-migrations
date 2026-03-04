package org.opensearch.migrations.bulkload.lucene;

import java.util.List;

public interface LuceneDocument {

    public List<? extends LuceneField> getFields();

}
