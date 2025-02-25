package org.opensearch.migrations.bulkload.lucene;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ReaderAndBase {
    MyLeafReader reader;
    int docBaseInParent;
}