package org.opensearch.migrations.replay;

// REBUILD-LIMBO(G10) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Test carried byte-identical. Unresolved: HttpMessageAndTimestamp . Per AGENTS.md section 4 an inherited test may stay broken while the architectures are partly connected; this one is restored by the milestone that rebuilds its subject, keeping its assertions conceptually stable while changing the mechanics.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G10)
/*

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SourceResponseNormalizerTest {
    private static final Instant FIRST_PACKET = Instant.ofEpochSecond(10);
    private static final Instant LAST_PACKET = Instant.ofEpochSecond(20);

    @Test
    void leavesOrdinaryResponseUnchanged() {
        var response = response("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK");

        Assertions.assertSame(response, SourceResponseNormalizer.retainTerminalResponse(response));
    }

    @Test
    void removesFragmentedInformationalResponsesBeforeTerminalResponse() {
        var response = new HttpMessageAndTimestamp.Response(FIRST_PACKET);
        response.add(asBytes("HTTP/1.1 100 Con"));
        response.add(asBytes("tinue\r\n\r\nHTTP/1.1 103 Early Hints\r\nLink: </style.css>\r\n\r\n"));
        response.add(asBytes("HTTP/1.1 201 Created\r\nContent-Length: 2\r\n\r\nOK"));
        response.setLastPacketTimestamp(LAST_PACKET);

        var normalized = SourceResponseNormalizer.retainTerminalResponse(response);

        Assertions.assertNotSame(response, normalized);
        Assertions.assertEquals(
            "HTTP/1.1 201 Created\r\nContent-Length: 2\r\n\r\nOK",
            asString(normalized)
        );
        Assertions.assertEquals(FIRST_PACKET, normalized.getFirstPacketTimestamp());
        Assertions.assertEquals(LAST_PACKET, normalized.getLastPacketTimestamp());
    }

    @Test
    void preservesSwitchingProtocolsAsTerminalResponse() {
        var response = response(
            "HTTP/1.1 101 Switching Protocols\r\n"
                + "Connection: upgrade\r\n"
                + "Upgrade: websocket\r\n\r\n"
        );

        Assertions.assertSame(response, SourceResponseNormalizer.retainTerminalResponse(response));
    }

    @Test
    void leavesIncompleteInformationalResponseForPrematureCloseDiagnostics() {
        var response = response("HTTP/1.1 100 Continue\r\nContent-Length:");

        Assertions.assertSame(response, SourceResponseNormalizer.retainTerminalResponse(response));
    }

    @Test
    void returnsNoTerminalResponseWhenOnlyCompleteInformationalResponsesWereCaptured() {
        var response = response("HTTP/1.1 100 Continue\r\n\r\n");

        Assertions.assertNull(SourceResponseNormalizer.retainTerminalResponse(response));
    }

    private static HttpMessageAndTimestamp.Response response(String value) {
        var response = new HttpMessageAndTimestamp.Response(FIRST_PACKET);
        response.add(asBytes(value));
        response.setLastPacketTimestamp(LAST_PACKET);
        return response;
    }

    private static byte[] asBytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static String asString(HttpMessageAndTimestamp response) {
        var bytes = response.packetBytes.stream()
            .reduce(
                new java.io.ByteArrayOutputStream(),
                (output, packet) -> {
                    output.write(packet, 0, packet.length);
                    return output;
                },
                (left, right) -> {
                    left.writeBytes(right.toByteArray());
                    return left;
                }
            )
            .toByteArray();
        return new String(bytes, StandardCharsets.US_ASCII);
    }
}

*/
// REBUILD-LIMBO-END(G10)