package org.opensearch.migrations.bulkload.solr;

import org.opensearch.migrations.bulkload.common.SnapshotReadFailure;

/**
 * Thrown when a Solr backup cannot be read because the expected directories or files are missing or
 * unreadable — e.g. the backup downloaded from S3 lacks the Lucene segments/index directories or the
 * shard metadata the reader expects to find.
 */
public class SolrBackupReadException extends IllegalStateException implements SnapshotReadFailure {
    public SolrBackupReadException(String message) {
        super(message);
    }

    public SolrBackupReadException(String message, Throwable cause) {
        super(message, cause);
    }
}
