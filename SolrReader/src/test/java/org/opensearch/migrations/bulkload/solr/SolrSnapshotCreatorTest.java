package org.opensearch.migrations.bulkload.solr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link SolrSnapshotCreator#extractS3Path(String)}.
 * Verifies that the Solr BACKUP API's {@code location} parameter is
 * computed correctly from an s3://bucket[/path] URI.
 */
class SolrSnapshotCreatorTest {

    @Test
    void bucketRootReturnsSlash() {
        assertEquals("/", SolrSnapshotCreator.extractS3Path("s3://my-bucket"));
        assertEquals("/", SolrSnapshotCreator.extractS3Path("s3://my-bucket/"));
    }

    @Test
    void singleLevelSubpathIsPreserved() {
        assertEquals("/foo", SolrSnapshotCreator.extractS3Path("s3://my-bucket/foo"));
    }

    @Test
    void nestedSubpathIsPreserved() {
        assertEquals("/foo/bar/baz", SolrSnapshotCreator.extractS3Path("s3://my-bucket/foo/bar/baz"));
    }

    @Test
    void trailingSlashIsTrimmed() {
        assertEquals("/foo", SolrSnapshotCreator.extractS3Path("s3://my-bucket/foo/"));
        assertEquals("/foo/bar", SolrSnapshotCreator.extractS3Path("s3://my-bucket/foo/bar/"));
    }

    @Test
    void subpathWithHyphens() {
        assertEquals("/solr-migration-v3",
            SolrSnapshotCreator.extractS3Path("s3://my-bucket/solr-migration-v3"));
    }
}
