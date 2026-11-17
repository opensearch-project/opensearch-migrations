/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.intake;

import java.nio.charset.StandardCharsets;

import org.opensearch.migrations.replay.SourceResponseFramingFallbackReason;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@WrapWithNettyLeakDetection
class SourceResponseFramingDetectorTest {

    @Test
    void contentLengthResponseCompletesOnlyAfterTheDeclaredBody() {
        try (var detector = new SourceResponseFramingDetector("GET")) {
            assertStatus(
                SourceResponseFramingDetector.Status.IN_PROGRESS,
                detector.accept(asBytes("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\n1234"))
            );
            assertStatus(
                SourceResponseFramingDetector.Status.COMPLETE,
                detector.accept(asBytes("5"))
            );
        }
    }

    @Test
    void contentLengthResponseCanBeSplitAtEveryByteBoundary() {
        var response = asBytes("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\n12345");
        for (int split = 1; split < response.length; ++split) {
            try (var detector = new SourceResponseFramingDetector("GET")) {
                var first = java.util.Arrays.copyOfRange(response, 0, split);
                var second = java.util.Arrays.copyOfRange(response, split, response.length);
                assertStatus(SourceResponseFramingDetector.Status.IN_PROGRESS, detector.accept(first));
                assertStatus(SourceResponseFramingDetector.Status.COMPLETE, detector.accept(second));
            }
        }
    }

    @Test
    void chunkedResponseCompletesAfterTerminalChunkAndTrailers() {
        try (var detector = new SourceResponseFramingDetector("GET")) {
            var response = asBytes(
                "HTTP/1.1 200 OK\r\n"
                    + "Transfer-Encoding: chunked\r\n\r\n"
                    + "4\r\nWiki\r\n"
                    + "5\r\npedia\r\n"
                    + "0\r\nX-Trailer: present\r\n\r\n"
            );
            for (int i = 0; i < response.length - 1; ++i) {
                assertStatus(
                    SourceResponseFramingDetector.Status.IN_PROGRESS,
                    detector.accept(new byte[] { response[i] })
                );
            }
            assertStatus(
                SourceResponseFramingDetector.Status.COMPLETE,
                detector.accept(new byte[] { response[response.length - 1] })
            );
        }
    }

    @Test
    void headResponseCompletesAfterHeadersDespiteContentLength() {
        try (var detector = new SourceResponseFramingDetector("HEAD")) {
            assertStatus(
                SourceResponseFramingDetector.Status.COMPLETE,
                detector.accept(asBytes("HTTP/1.1 200 OK\r\nContent-Length: 12345\r\n\r\n"))
            );
        }
    }

    @Test
    void bodylessStatusesCompleteAfterHeaders() {
        for (var statusLine : new String[] {
            "HTTP/1.1 204 No Content\r\nContent-Length: 10\r\n\r\n",
            "HTTP/1.1 304 Not Modified\r\nContent-Length: 10\r\n\r\n"
        }) {
            try (var detector = new SourceResponseFramingDetector("GET")) {
                assertStatus(
                    SourceResponseFramingDetector.Status.COMPLETE,
                    detector.accept(asBytes(statusLine))
                );
            }
        }
    }

    @Test
    void interimResponseDoesNotCompleteTheExchange() {
        try (var detector = new SourceResponseFramingDetector("POST")) {
            assertStatus(
                SourceResponseFramingDetector.Status.IN_PROGRESS,
                detector.accept(asBytes("HTTP/1.1 100 Continue\r\n\r\n"))
            );
            assertStatus(
                SourceResponseFramingDetector.Status.COMPLETE,
                detector.accept(asBytes("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK"))
            );
        }
    }

    @Test
    void firstFinalResponseRemainsCompleteWhenTheSameInputContainsTrailingData() {
        for (var trailingData : new String[] {
            "unexpected trailing response bytes",
            "HTTP/1.1 599 Bogus Second Response\r\nContent-Length: 0\r\n\r\n"
        }) {
            try (var detector = new SourceResponseFramingDetector("GET")) {
                assertStatus(
                    SourceResponseFramingDetector.Status.COMPLETE,
                    detector.accept(asBytes(
                        "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n" + trailingData
                    ))
                );
            }
        }
    }

    @Test
    void closeDelimitedResponseRemainsInProgressWithoutAConnectionBoundary() {
        var detector = new SourceResponseFramingDetector("GET");
        assertStatus(
            SourceResponseFramingDetector.Status.IN_PROGRESS,
            detector.accept(asBytes("HTTP/1.0 200 OK\r\n\r\nbody without an on-wire terminator"))
        );
        detector.close();
        assertStatus(SourceResponseFramingDetector.Status.IN_PROGRESS, detector.result());
    }

    @Test
    void conflictingContentLengthsFallBackWithoutClaimingCompletion() {
        try (var detector = new SourceResponseFramingDetector("GET")) {
            var result = detector.accept(asBytes(
                "HTTP/1.1 200 OK\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\n12"
            ));
            Assertions.assertEquals(SourceResponseFramingDetector.Status.FALLBACK, result.status());
            Assertions.assertEquals(
                SourceResponseFramingFallbackReason.MALFORMED_RESPONSE,
                result.fallbackReason()
            );
        }
    }

    @Test
    void switchingProtocolsFallsBack() {
        try (var detector = new SourceResponseFramingDetector("GET")) {
            var result = detector.accept(asBytes(
                "HTTP/1.1 101 Switching Protocols\r\nConnection: upgrade\r\nUpgrade: websocket\r\n\r\n"
            ));
            Assertions.assertEquals(SourceResponseFramingDetector.Status.FALLBACK, result.status());
            Assertions.assertEquals(
                SourceResponseFramingFallbackReason.UNSUPPORTED_UPGRADE,
                result.fallbackReason()
            );
        }
    }

    @Test
    void successfulConnectFallsBack() {
        try (var detector = new SourceResponseFramingDetector("CONNECT")) {
            var result = detector.accept(asBytes(
                "HTTP/1.1 200 Connection Established\r\n\r\n"
            ));
            Assertions.assertEquals(SourceResponseFramingDetector.Status.FALLBACK, result.status());
            Assertions.assertEquals(
                SourceResponseFramingFallbackReason.UNSUPPORTED_UPGRADE,
                result.fallbackReason()
            );
        }
    }

    @Test
    void largeBodyIsStreamedWithoutDecodedMessagesEscapingTheDetector() {
        var bodySize = 4 * 1024 * 1024;
        var body = new byte[64 * 1024];
        try (var detector = new SourceResponseFramingDetector("GET")) {
            assertStatus(
                SourceResponseFramingDetector.Status.IN_PROGRESS,
                detector.accept(asBytes("HTTP/1.1 200 OK\r\nContent-Length: " + bodySize + "\r\n\r\n"))
            );
            for (int accepted = 0; accepted < bodySize; accepted += body.length) {
                var result = detector.accept(body);
                var expected = accepted + body.length == bodySize
                    ? SourceResponseFramingDetector.Status.COMPLETE
                    : SourceResponseFramingDetector.Status.IN_PROGRESS;
                assertStatus(expected, result);
            }
        }
    }

    private static byte[] asBytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static void assertStatus(
        SourceResponseFramingDetector.Status expected,
        SourceResponseFramingDetector.Result actual
    ) {
        Assertions.assertEquals(expected, actual.status());
    }
}
