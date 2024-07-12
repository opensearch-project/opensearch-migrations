package org.opensearch.migrations.replay;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Random;

import org.opensearch.migrations.testutils.HttpRequestFirstLine;
import org.opensearch.migrations.testutils.SimpleHttpResponse;

import lombok.Lombok;

public class TestHttpServerContext {

    private TestHttpServerContext() {}

    public static final int MAX_RESPONSE_TIME_MS = 250;

    public static final String SERVER_RESPONSE_BODY_PREFIX = "Boring Response to ";

    static String getUriForIthRequest(int i) {
        return String.format("/%04d", i);
    }

    static String getRequestString(int i) {
        return String.format(
            "GET %s HTTP/1.1\r\n"
                + "Connection: Keep-Alive\r\n"
                + "Host: localhost\r\n"
                + "User-Agent: UnitTest\r\n"
                + "Connection: Keep-Alive\r\n"
                + "\r\n",
            getUriForIthRequest(i)
        );
    }

    public static SimpleHttpResponse makeResponse(Random rand, HttpRequestFirstLine firstLine) {
        return makeResponse(firstLine, Duration.ofMillis(rand.nextInt(MAX_RESPONSE_TIME_MS)));
    }

    public static SimpleHttpResponse makeResponse(HttpRequestFirstLine r, Duration responseWaitTime) {
        try {
            Thread.sleep(responseWaitTime.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw Lombok.sneakyThrow(e);
        }
        String body = SERVER_RESPONSE_BODY_PREFIX + r.path();
        var payloadBytes = body.getBytes(StandardCharsets.UTF_8);
        var headers = Map.of(
            "Content-Type",
            "text/plain",
            "Funtime",
            "checkIt!",
            "Content-Length",
            "" + payloadBytes.length
        );
        return new SimpleHttpResponse(headers, payloadBytes, "OK", 200);
    }
}
