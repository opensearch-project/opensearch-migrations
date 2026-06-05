package org.opensearch.migrations.bulkload.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class GcsUriTest {

    @Test
    void ParsesBucketOnly() {
        GcsUri uri = new GcsUri("gs://my-bucket");
        assertEquals("my-bucket", uri.bucketName);
        assertEquals("", uri.key);
        assertEquals("gs://my-bucket", uri.uri);
    }

    @Test
    void ParsesBucketWithTrailingSlash() {
        GcsUri uri = new GcsUri("gs://my-bucket/");
        assertEquals("my-bucket", uri.bucketName);
        assertEquals("", uri.key);
        assertEquals("gs://my-bucket", uri.uri);
    }

    @Test
    void ParsesBucketWithSinglePathSegment() {
        GcsUri uri = new GcsUri("gs://my-bucket/my-path");
        assertEquals("my-bucket", uri.bucketName);
        assertEquals("my-path", uri.key);
        assertEquals("gs://my-bucket/my-path", uri.uri);
    }

    @Test
    void ParsesBucketWithNestedPath() {
        GcsUri uri = new GcsUri("gs://my-bucket/path/to/snapshots");
        assertEquals("my-bucket", uri.bucketName);
        assertEquals("path/to/snapshots", uri.key);
        assertEquals("gs://my-bucket/path/to/snapshots", uri.uri);
    }

    @Test
    void ParsesBucketWithNestedPathAndTrailingSlash() {
        GcsUri uri = new GcsUri("gs://my-bucket/path/to/snapshots/");
        assertEquals("my-bucket", uri.bucketName);
        assertEquals("path/to/snapshots", uri.key);
        assertEquals("gs://my-bucket/path/to/snapshots", uri.uri);
    }

    @Test
    void ThrowsForNullInput() {
        assertThrows(IllegalArgumentException.class, () -> new GcsUri(null));
    }

    @Test
    void ThrowsForNonGcsScheme() {
        assertThrows(IllegalArgumentException.class, () -> new GcsUri("s3://my-bucket/path"));
    }

    @Test
    void ThrowsForEmptyBucket() {
        assertThrows(IllegalArgumentException.class, () -> new GcsUri("gs:///path"));
    }

    @Test
    void ThrowsForMissingScheme() {
        assertThrows(IllegalArgumentException.class, () -> new GcsUri("my-bucket/path"));
    }
}
