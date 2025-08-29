package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;

public interface LuceneLeafReader {

    public LuceneDocument document(int luceneDocId) throws IOException;

    public BitSetConverter.FixedLengthBitSet getLiveDocs();

    public int maxDoc();

    public String getContextString();

    public String getSegmentName();

    public String getSegmentInfoString();

}
