package org.opensearch.migrations;

/**
 * Strategy interface for creating backups/snapshots from a source cluster.
 * Implementations handle the specifics of each source type (Elasticsearch, Solr, etc.).
 */
public interface SourceBackupStrategy {
    void run();
}
