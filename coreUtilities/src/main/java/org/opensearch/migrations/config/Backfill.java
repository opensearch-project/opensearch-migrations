package org.opensearch.migrations.config;

public class Backfill {
    // Ignoring since OSI config isn't checked by java based tools
    public Object opensearch_ingestion;
    public ReindexFromSnapshot reindex_from_snapshot;
}
