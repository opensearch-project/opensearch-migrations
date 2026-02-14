package org.opensearch.migrations.replay.bugfixes;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.opensearch.migrations.replay.HttpByteBufFormatter;
import org.opensearch.migrations.replay.HttpMessageAndTimestamp;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Bug 8: HttpMessageAndTimestamp.Response.toString() passes HttpMessageType.REQUEST
 * instead of HttpMessageType.RESPONSE to the formatter.
 *
 * This is a copy-paste error from the Request class. Response data is parsed/displayed
 * as if it were a request, producing garbled log output.
 *
 * This test asserts on the CURRENT BUGGY behavior (Response uses REQUEST type).
 * When the bug is fixed, this test should FAIL.
 */
@Slf4j
public class ResponseToStringUsesRequestTypeBugTest {

    @Test
    void responseToString_usesRequestType_insteadOfResponseType() {
        var response = new HttpMessageAndTimestamp.Response(Instant.now());
        response.add("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.UTF_8));

        var toString = response.toString();
        log.info("Response.toString() = {}", toString);

        // The format() method is called with HttpMessageType.REQUEST (the bug).
        // When parsing HTTP response bytes as a REQUEST, the formatter tries to parse
        // "HTTP/1.1 200 OK" as a request line (method URI version), which produces
        // different output than parsing it as a response status line.
        //
        // With REQUEST type, httpPacketBytesToString tries to decode as HttpRequest.
        // With RESPONSE type, it would decode as HttpResponse.
        //
        // We can verify the bug by checking that the toString output does NOT contain
        // a properly parsed response status line. When parsed as REQUEST, the formatter
        // will either fail to parse or produce garbled output.

        // BUG ASSERTION: The Response class formats itself using REQUEST type.
        // We verify this by creating a Request with the same bytes and checking
        // that both produce identical toString output (they shouldn't if types were correct).
        var request = new HttpMessageAndTimestamp.Request(Instant.now());
        request.add("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.UTF_8));

        var requestToString = request.toString();
        log.info("Request.toString() = {}", requestToString);

        // Both use HttpMessageType.REQUEST in format(), so the formatted message portion
        // should be identical (both parse the same bytes with the same type).
        // When the bug is fixed, Response will use RESPONSE type and produce different output.
        var responseMessage = extractMessagePortion(toString);
        var requestMessage = extractMessagePortion(requestToString);

        Assertions.assertEquals(requestMessage, responseMessage,
            "BUG: Response.toString() produces the same formatted output as Request.toString() "
            + "because both use HttpMessageType.REQUEST. When fixed, they should differ.");
    }

    /**
     * Extract the message=[...] portion from the toString output.
     */
    private String extractMessagePortion(String fullString) {
        int start = fullString.indexOf("message=[");
        int end = fullString.lastIndexOf("]}");
        if (start < 0 || end < 0) {
            return fullString;
        }
        return fullString.substring(start, end + 2);
    }
}
