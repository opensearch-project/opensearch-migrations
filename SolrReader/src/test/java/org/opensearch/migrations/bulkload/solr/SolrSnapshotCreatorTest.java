package org.opensearch.migrations.bulkload.solr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SolrSnapshotCreator}'s backup {@code location} computation.
 * Verifies that the Solr BACKUP API's {@code location} parameter is computed correctly
 * from s3://bucket[/path] and gs://bucket[/path] URIs (the bucket itself is configured in
 * solr.xml, so {@code location} must be the bucket-relative path).
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

    // extractS3Path is scheme-agnostic (URI.getPath()); confirm it strips a gs:// bucket the same way.
    @Test
    void extractS3Path_gcsUri_stripsSchemeAndBucket() {
        assertEquals("/", SolrSnapshotCreator.extractS3Path("gs://my-bucket"));
        assertEquals("/", SolrSnapshotCreator.extractS3Path("gs://my-bucket/"));
        assertEquals("/foo", SolrSnapshotCreator.extractS3Path("gs://my-bucket/foo"));
        assertEquals("/foo/bar", SolrSnapshotCreator.extractS3Path("gs://my-bucket/foo/bar/"));
    }

    @Test
    void isCloudRepoUri_recognizesS3AndGcs() {
        assertTrue(SolrSnapshotCreator.isCloudRepoUri("s3://my-bucket/foo"));
        assertTrue(SolrSnapshotCreator.isCloudRepoUri("gs://my-bucket/foo"));
        assertFalse(SolrSnapshotCreator.isCloudRepoUri("/var/solr/data"));
        assertFalse(SolrSnapshotCreator.isCloudRepoUri("file:///var/solr/data"));
        assertFalse(SolrSnapshotCreator.isCloudRepoUri(null));
    }

    // buildPerCollectionLocation is what flows into Solr's BACKUP `location` param. For cloud URIs
    // (s3:// AND gs://) it must yield the bucket-relative <path>/<snapshotName>; for a bare
    // filesystem path it appends <snapshotName> to the path as-is.
    @Test
    void buildPerCollectionLocation_gcsBucketRoot() {
        assertEquals("/snap1",
            SolrSnapshotCreator.buildPerCollectionLocation("gs://my-bucket", "snap1"));
    }

    @Test
    void buildPerCollectionLocation_gcsSubpath() {
        assertEquals("/migration-v1/snap1",
            SolrSnapshotCreator.buildPerCollectionLocation("gs://my-bucket/migration-v1", "snap1"));
    }

    @Test
    void buildPerCollectionLocation_s3SubpathStillWorks() {
        assertEquals("/migration-v1/snap1",
            SolrSnapshotCreator.buildPerCollectionLocation("s3://my-bucket/migration-v1", "snap1"));
    }

    @Test
    void buildPerCollectionLocation_filesystemPathAppendsSnapshot() {
        assertEquals("/var/solr/data/snap1",
            SolrSnapshotCreator.buildPerCollectionLocation("/var/solr/data", "snap1"));
        assertEquals("/var/solr/data/snap1",
            SolrSnapshotCreator.buildPerCollectionLocation("/var/solr/data/", "snap1"));
    }

    @Test
    void buildPerCollectionLocation_nullLocationReturnsNull() {
        assertNull(SolrSnapshotCreator.buildPerCollectionLocation(null, "snap1"));
    }
}
