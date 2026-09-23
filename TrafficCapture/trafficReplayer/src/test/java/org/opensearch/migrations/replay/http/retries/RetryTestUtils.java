package org.opensearch.migrations.replay.http.retries;

// REBUILD-LIMBO(G10) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Test carried byte-identical. Unresolved: HttpMessageAndTimestamp IRequestResponsePacketPair . Per AGENTS.md section 4 an inherited test may stay broken while the architectures are partly connected; this one is restored by the milestone that rebuilds its subject, keeping its assertions conceptually stable while changing the mechanics.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G10)
/*

import java.time.Instant;

import org.opensearch.migrations.replay.HttpMessageAndTimestamp;
import org.opensearch.migrations.replay.IRequestResponsePacketPair;

import lombok.Getter;

class RetryTestUtils {

    public static final String GET_SLASH_REQUEST =
        "GET / HTTP/1.1\r\n" +
            "User-Agent: Unit Test\r\n" +
            "Host: localhost\r\n\r\n";

    public static String makeSlashResponse(int statusCode) {
        return "HTTP/1.1 " + statusCode + " OK\r\n" +
            "Content-Length: 2\r\n" +
            "Content-Type: text/plain\r\n\r\n" +
            "hi\r\n";
    }

    @Getter
    public static class TestRequestResponsePair implements IRequestResponsePacketPair {
        HttpMessageAndTimestamp responseData;

        @Override public HttpMessageAndTimestamp getRequestData() { throw new IllegalStateException(); }

        public TestRequestResponsePair(byte[] bytes) {
            responseData = new HttpMessageAndTimestamp(Instant.now());
            responseData.add(bytes);
        }
    }


}

*/
// REBUILD-LIMBO-END(G10)