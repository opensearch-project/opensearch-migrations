package org.opensearch.migrations.bulkload.lucene;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ReaderAndBase {
    LuceneLeafReader reader;
    int docBaseInParent;
    BitSetConverter.FixedLengthBitSet liveDocs;
}
