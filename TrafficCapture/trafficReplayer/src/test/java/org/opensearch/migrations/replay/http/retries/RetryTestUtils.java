package org.opensearch.migrations.replay.http.retries;

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
