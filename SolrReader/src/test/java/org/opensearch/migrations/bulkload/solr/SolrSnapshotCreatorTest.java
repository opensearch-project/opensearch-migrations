package org.opensearch.migrations.bulkload.solr;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

class SolrSnapshotCreatorTest {

    @Test
    void extractS3Path_withSubpath() {
        assertThat(SolrSnapshotCreator.extractS3Path("s3://bucket/solr-migration-v3"), equalTo("/solr-migration-v3"));
    }

    @Test
    void extractS3Path_withNestedSubpath() {
        assertThat(SolrSnapshotCreator.extractS3Path("s3://bucket/some/nested/path"), equalTo("/some/nested/path"));
    }

    @Test
    void extractS3Path_bucketOnly() {
        assertThat(SolrSnapshotCreator.extractS3Path("s3://bucket"), equalTo("/"));
    }

    @Test
    void extractS3Path_bucketWithTrailingSlash() {
        assertThat(SolrSnapshotCreator.extractS3Path("s3://bucket/"), equalTo("/"));
    }

    @Test
    void extractS3Path_withTrailingSlash() {
        assertThat(SolrSnapshotCreator.extractS3Path("s3://bucket/solr-migration-v3/"), equalTo("/solr-migration-v3/"));
    }
}
