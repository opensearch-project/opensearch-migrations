package org.opensearch.migrations.replay;

import org.opensearch.migrations.testutils.HttpFirstLine;
import org.opensearch.migrations.testutils.SimpleHttpResponse;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

public class TestHttpServerContext {

    public static final int MAX_RESPONSE_TIME_MS = 250;

    public static String SERVER_RESPONSE_BODY_PREFIX = "Boring Response to ";

    static String getUriForIthRequest(int i) {
        return String.format("/%04d", i);
    }

    static String getRequestString(int i) {
        return String.format("GET %s HTTP/1.1\r\n" +
                        "Connection: Keep-Alive\r\n" +
                        "Host: localhost\r\n" +
                        "User-Agent: UnitTest\r\n" +
                        "Connection: Keep-Alive\r\n" +
                        "\r\n",
                getUriForIthRequest(i));
    }

    public static SimpleHttpResponse makeResponse(HttpFirstLine r) {
        return makeResponse(r, Duration.ofMillis((int)(Math.random()* MAX_RESPONSE_TIME_MS)));
    }

    public static SimpleHttpResponse makeResponse(HttpFirstLine r, Duration responseWaitTime) {
        try {
            Thread.sleep(responseWaitTime.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        String body = SERVER_RESPONSE_BODY_PREFIX + r.path();
        var payloadBytes = body.getBytes(StandardCharsets.UTF_8);
        var headers = Map.of(
                "Content-Type", "text/plain",
                "Funtime", "checkIt!",
                "Content-Length", ""+payloadBytes.length);
        return new SimpleHttpResponse(headers, payloadBytes, "OK", 200);
    }

}
