package org.opensearch.migrations.bulkload.common;

import lombok.AllArgsConstructor;

@AllArgsConstructor
public class RfsLuceneDocument {
    public final String id;
    public final String type;
    public final String source;
    public final String routing;

    public RfsLuceneDocument(String id, String type, String source) {
        this(id, type, source, null);
    }
}
