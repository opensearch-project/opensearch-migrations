package org.opensearch.migrations.bulkload.common;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class RfsLuceneDocument {
    public final int luceneSegId;
    public final int luceneDocId;
    public final String osDocId;
    public final String type;
    public final String source;
}
