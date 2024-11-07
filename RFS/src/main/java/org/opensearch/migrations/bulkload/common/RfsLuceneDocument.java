package org.opensearch.migrations.bulkload.common;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class RfsLuceneDocument {
    public final String id;
    public final String type;
    public final String source;
}
