package org.opensearch.migrations.bulkload.common;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.bulkload.common.http.ConnectionContext;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestClientTest {

    private ConnectionContext ctx(String url) {
        var args = new ConnectionContext.TargetArgs();
        args.host = url;
        return args.toConnectionContext();
    }

    @Test
    void getHostHeaderValue_httpDefaultPort() {
        assertEquals("localhost", RestClient.getHostHeaderValue(ctx("http://localhost")));
    }

    @Test
    void getHostHeaderValue_httpPort80() {
        assertEquals("localhost", RestClient.getHostHeaderValue(ctx("http://localhost:80")));
    }

    @Test
    void getHostHeaderValue_httpCustomPort() {
        assertEquals("localhost:9200", RestClient.getHostHeaderValue(ctx("http://localhost:9200")));
    }

    @Test
    void getHostHeaderValue_httpsDefaultPort() {
        assertEquals("search.example.com", RestClient.getHostHeaderValue(ctx("https://search.example.com")));
    }

    @Test
    void getHostHeaderValue_httpsPort443() {
        assertEquals("search.example.com", RestClient.getHostHeaderValue(ctx("https://search.example.com:443")));
    }

    @Test
    void getHostHeaderValue_httpsCustomPort() {
        assertEquals("search.example.com:9243", RestClient.getHostHeaderValue(ctx("https://search.example.com:9243")));
    }

    @Test
    void addGzipResponseHeaders_addsCorrectHeader() {
        Map<String, List<String>> headers = new HashMap<>();
        RestClient.addGzipResponseHeaders(headers);
        assertTrue(RestClient.hasGzipResponseHeaders(headers));
    }

    @Test
    void hasGzipResponseHeaders_falseWhenMissing() {
        assertFalse(RestClient.hasGzipResponseHeaders(Map.of()));
    }

    @Test
    void addGzipRequestHeaders_addsContentEncoding() {
        Map<String, List<String>> headers = new HashMap<>();
        RestClient.addGzipRequestHeaders(headers);
        assertEquals(List.of("gzip"), headers.get("content-encoding"));
    }
}
