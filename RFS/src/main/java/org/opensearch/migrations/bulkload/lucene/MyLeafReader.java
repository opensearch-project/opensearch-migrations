package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;

public interface MyLeafReader {

    public MyDocument document(int luceneDocId) throws IOException;

    public MyLiveDocs getLiveDocs();

    public int maxDoc();

    public String getContextString();

    public String getSegmentName();

    public String getSegmentInfoString();

}
