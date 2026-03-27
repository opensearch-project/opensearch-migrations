package org.opensearch.migrations.utils;

import java.net.URI;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class URIHelperTest {

    @Test
    void testHttpsWithNoPortInfersPort443() {
        URI uri = URIHelper.parseUriWithDefaultPort("https://search-my-domain.us-east-1.es.amazonaws.com");
        Assertions.assertEquals(443, uri.getPort());
        Assertions.assertEquals("https", uri.getScheme());
    }

    @Test
    void testHttpWithNoPortInfersPort80() {
        URI uri = URIHelper.parseUriWithDefaultPort("http://search-my-domain.us-east-1.es.amazonaws.com");
        Assertions.assertEquals(80, uri.getPort());
        Assertions.assertEquals("http", uri.getScheme());
    }

    @Test
    void testExplicitPortIsPreserved() {
        URI uri = URIHelper.parseUriWithDefaultPort("https://search-my-domain.us-east-1.es.amazonaws.com:9200");
        Assertions.assertEquals(9200, uri.getPort());
    }

    @Test
    void testExplicitDefaultPortIsPreserved() {
        URI uri = URIHelper.parseUriWithDefaultPort("https://search-my-domain.us-east-1.es.amazonaws.com:443");
        Assertions.assertEquals(443, uri.getPort());
    }

    @Test
    void testMissingSchemeThrows() {
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> URIHelper.parseUriWithDefaultPort("search-my-domain.us-east-1.es.amazonaws.com:9200")
        );
    }

    @Test
    void testMissingHostThrows() {
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> URIHelper.parseUriWithDefaultPort("https://:443")
        );
    }

    @Test
    void testUnknownSchemeWithNoPortThrows() {
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> URIHelper.parseUriWithDefaultPort("ftp://example.com")
        );
    }
}
