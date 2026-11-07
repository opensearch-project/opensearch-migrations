package org.opensearch.migrations.bulkload.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class AzureUriTest {

    @Test
    void ParsesAccountAndContainerOnly() {
        AzureUri uri = new AzureUri("azure://myaccount/my-container");
        assertEquals("myaccount", uri.accountName);
        assertEquals("my-container", uri.containerName);
        assertEquals("", uri.key);
        assertEquals("azure://myaccount/my-container", uri.uri);
    }

    @Test
    void ParsesContainerWithTrailingSlash() {
        AzureUri uri = new AzureUri("azure://myaccount/my-container/");
        assertEquals("myaccount", uri.accountName);
        assertEquals("my-container", uri.containerName);
        assertEquals("", uri.key);
        assertEquals("azure://myaccount/my-container", uri.uri);
    }

    @Test
    void ParsesSinglePathSegment() {
        AzureUri uri = new AzureUri("azure://myaccount/my-container/my-path");
        assertEquals("myaccount", uri.accountName);
        assertEquals("my-container", uri.containerName);
        assertEquals("my-path", uri.key);
        assertEquals("azure://myaccount/my-container/my-path", uri.uri);
    }

    @Test
    void ParsesNestedPath() {
        AzureUri uri = new AzureUri("azure://myaccount/my-container/path/to/snapshots");
        assertEquals("myaccount", uri.accountName);
        assertEquals("my-container", uri.containerName);
        assertEquals("path/to/snapshots", uri.key);
        assertEquals("azure://myaccount/my-container/path/to/snapshots", uri.uri);
    }

    @Test
    void ParsesNestedPathWithTrailingSlash() {
        AzureUri uri = new AzureUri("azure://myaccount/my-container/path/to/snapshots/");
        assertEquals("myaccount", uri.accountName);
        assertEquals("my-container", uri.containerName);
        assertEquals("path/to/snapshots", uri.key);
        assertEquals("azure://myaccount/my-container/path/to/snapshots", uri.uri);
    }

    @Test
    void ThrowsForNullInput() {
        assertThrows(IllegalArgumentException.class, () -> new AzureUri(null));
    }

    @Test
    void ThrowsForNonAzureScheme() {
        assertThrows(IllegalArgumentException.class, () -> new AzureUri("gs://my-bucket/path"));
    }

    @Test
    void ThrowsForEmptyAccount() {
        assertThrows(IllegalArgumentException.class, () -> new AzureUri("azure:///my-container/path"));
    }

    @Test
    void ThrowsForMissingContainer() {
        assertThrows(IllegalArgumentException.class, () -> new AzureUri("azure://myaccount"));
    }

    @Test
    void ThrowsForEmptyContainer() {
        assertThrows(IllegalArgumentException.class, () -> new AzureUri("azure://myaccount//path"));
    }

    @Test
    void ThrowsForMissingScheme() {
        assertThrows(IllegalArgumentException.class, () -> new AzureUri("myaccount/my-container/path"));
    }
}
