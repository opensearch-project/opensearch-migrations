package org.opensearch.migrations.replay;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Verifies that HttpMessageAndTimestamp.Response.toString() passes HttpMessageType.RESPONSE
 * to the formatter, not HttpMessageType.REQUEST.
 *
 * This test verifies that Response uses RESPONSE type, producing different output
 * than Request for the same bytes when parsed as HTTP.
 */
@Slf4j
public class ResponseToStringUsesRequestTypeTest {

    @Test
    void responseToString_usesCorrectResponseType() throws Exception {
        var response = new HttpMessageAndTimestamp.Response(Instant.now());
        response.add("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.UTF_8));

        var request = new HttpMessageAndTimestamp.Request(Instant.now());
        request.add("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.UTF_8));

        // Use PARSED_HTTP mode so the formatter actually uses the message type
        HttpByteBufFormatter.setPrintStyleForCallable(
            HttpByteBufFormatter.PacketPrintFormat.PARSED_HTTP,
            () -> {
                var responseStr = response.toString();
                var requestStr = request.toString();
                log.info("Response.toString() = {}", responseStr);
                log.info("Request.toString() = {}", requestStr);

                var responseMessage = extractMessagePortion(responseStr);
                var requestMessage = extractMessagePortion(requestStr);

                // Response uses RESPONSE type, Request uses REQUEST type,
                // so they produce different parsed output for the same bytes
                Assertions.assertNotEquals(requestMessage, responseMessage,
                    "Response.toString() should produce different output than Request.toString() "
                    + "because they use different HttpMessageType");
                return null;
            }
        );
    }

    private String extractMessagePortion(String fullString) {
        int start = fullString.indexOf("message=[");
        int end = fullString.lastIndexOf("]}");
        if (start < 0 || end < 0) {
            return fullString;
        }
        return fullString.substring(start, end + 2);
    }
}
