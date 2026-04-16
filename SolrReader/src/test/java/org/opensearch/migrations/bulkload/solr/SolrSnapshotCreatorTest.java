package org.opensearch.migrations.bulkload.solr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SolrSnapshotCreatorTest {

    @Test
    void extractS3Path_withSubpath() {
        assertEquals("/solr-migration-v3", SolrSnapshotCreator.extractS3Path("s3://my-bucket/solr-migration-v3"));
    }

    @Test
    void extractS3Path_withNestedSubpath() {
        assertEquals("/some/nested/path", SolrSnapshotCreator.extractS3Path("s3://my-bucket/some/nested/path"));
    }

    @Test
    void extractS3Path_bucketOnly() {
        assertEquals("/", SolrSnapshotCreator.extractS3Path("s3://my-bucket"));
    }

    @Test
    void extractS3Path_bucketWithTrailingSlash() {
        assertEquals("/", SolrSnapshotCreator.extractS3Path("s3://my-bucket/"));
    }

    @Test
    void extractS3Path_subpathWithTrailingSlash() {
        assertEquals("/solr-migration-v3/", SolrSnapshotCreator.extractS3Path("s3://my-bucket/solr-migration-v3/"));
    }
}
