package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.util.BitSet;

public interface LuceneLeafReader {

    public LuceneDocument document(int luceneDocId) throws IOException;

    public BitSet getLiveDocs();

    public int maxDoc();

    public String getContextString();

    public String getSegmentName();

    public String getSegmentInfoString();

}
