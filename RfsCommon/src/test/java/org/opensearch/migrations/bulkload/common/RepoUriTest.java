package org.opensearch.migrations.bulkload.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class RepoUriTest {

    @Test
    void parse_fileScheme_returnsFileRepoUri() {
        var uri = RepoUri.parse("file:///mnt/snapshots");
        assertInstanceOf(RepoUri.FileRepoUri.class, uri);
        assertEquals("/mnt/snapshots", ((RepoUri.FileRepoUri) uri).path());
        assertEquals("file:///mnt/snapshots", uri.rawUri());
    }

    @Test
    void parse_bareAbsolutePath_returnsFileRepoUri() {
        var uri = RepoUri.parse("/var/data/snapshots");
        assertInstanceOf(RepoUri.FileRepoUri.class, uri);
        assertEquals("/var/data/snapshots", ((RepoUri.FileRepoUri) uri).path());
        assertEquals("file:///var/data/snapshots", uri.rawUri());
    }

    @Test
    void parse_s3Scheme_returnsS3RepoUri() {
        var uri = RepoUri.parse("s3://my-bucket/path/to/repo");
        assertInstanceOf(RepoUri.S3RepoUri.class, uri);
        var s3 = (RepoUri.S3RepoUri) uri;
        assertEquals("my-bucket", s3.s3Uri().bucketName);
        assertEquals("path/to/repo", s3.s3Uri().key);
        assertEquals("s3://my-bucket/path/to/repo", uri.rawUri());
    }

    @Test
    void parse_s3BucketOnly_returnsS3RepoUri() {
        var uri = RepoUri.parse("s3://my-bucket");
        assertInstanceOf(RepoUri.S3RepoUri.class, uri);
        var s3 = (RepoUri.S3RepoUri) uri;
        assertEquals("my-bucket", s3.s3Uri().bucketName);
        assertEquals("", s3.s3Uri().key);
    }

    @Test
    void parse_gsScheme_returnsGcsRepoUri() {
        var uri = RepoUri.parse("gs://my-gcs-bucket/snapshots");
        assertInstanceOf(RepoUri.GcsRepoUri.class, uri);
        assertEquals("gs://my-gcs-bucket/snapshots", uri.rawUri());
    }

    @Test
    void parse_unrecognizedScheme_throwsIllegalArgumentException() {
        var ex = assertThrows(IllegalArgumentException.class, () -> RepoUri.parse("ftp://host/path"));
        assertEquals(
            "Unrecognized repo URI scheme: 'ftp://host/path'. Expected file://, s3://, gs://, or an absolute path.",
            ex.getMessage()
        );
    }

    @Test
    void parse_relativePath_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> RepoUri.parse("relative/path"));
    }
}
