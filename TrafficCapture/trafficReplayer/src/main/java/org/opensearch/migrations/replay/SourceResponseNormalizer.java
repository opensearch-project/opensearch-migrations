package org.opensearch.migrations.replay;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Removes complete informational responses that precede a captured source response.
 *
 * <p>The capture format records raw writes, so an HTTP server may place one or more informational
 * responses in front of the terminal response. Downstream source-status classification must see
 * the terminal response rather than the first {@code 1xx} status.
 */
final class SourceResponseNormalizer {
    private static final byte[] HTTP_PREFIX = "HTTP/".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private SourceResponseNormalizer() {}

    static HttpMessageAndTimestamp retainTerminalResponse(HttpMessageAndTimestamp response) {
        if (response == null || response.hasInProgressSegment()) {
            return response;
        }

        var bytes = concatenate(response);
        int terminalOffset = findTerminalResponseOffset(bytes);
        if (terminalOffset == 0) {
            return response;
        }
        if (terminalOffset == bytes.length) {
            return null;
        }

        var terminalResponse = new HttpMessageAndTimestamp.Response(response.getFirstPacketTimestamp());
        terminalResponse.add(Arrays.copyOfRange(bytes, terminalOffset, bytes.length));
        terminalResponse.setLastPacketTimestamp(response.getLastPacketTimestamp());
        return terminalResponse;
    }

    private static byte[] concatenate(HttpMessageAndTimestamp response) {
        var output = new ByteArrayOutputStream();
        response.packetBytes.forEach(bytes -> output.write(bytes, 0, bytes.length));
        return output.toByteArray();
    }

    private static int findTerminalResponseOffset(byte[] bytes) {
        int offset = 0;
        while (offset < bytes.length) {
            int statusCode = parseStatusCode(bytes, offset);
            if (!isDiscardableInformationalStatus(statusCode)) {
                return offset;
            }

            int headerEnd = findHeaderEnd(bytes, offset);
            if (headerEnd < 0) {
                return offset;
            }
            offset = headerEnd;
        }
        return offset;
    }

    private static int parseStatusCode(byte[] bytes, int offset) {
        if (!startsWith(bytes, offset, HTTP_PREFIX)) {
            return -1;
        }

        int lineEnd = findLineEnd(bytes, offset);
        if (lineEnd < 0) {
            return -1;
        }
        int firstSpace = indexOf(bytes, (byte) ' ', offset, lineEnd);
        if (firstSpace < 0) {
            return -1;
        }

        int statusStart = firstSpace + 1;
        while (statusStart < lineEnd && bytes[statusStart] == ' ') {
            statusStart++;
        }
        if (statusStart + 3 > lineEnd) {
            return -1;
        }
        int first = digitValue(bytes[statusStart]);
        int second = digitValue(bytes[statusStart + 1]);
        int third = digitValue(bytes[statusStart + 2]);
        if (first < 0 || second < 0 || third < 0) {
            return -1;
        }
        return first * 100 + second * 10 + third;
    }

    private static boolean isDiscardableInformationalStatus(int statusCode) {
        return statusCode >= 100 && statusCode < 200 && statusCode != 101;
    }

    private static int findHeaderEnd(byte[] bytes, int offset) {
        for (int i = offset; i < bytes.length - 1; i++) {
            if (i + 3 < bytes.length
                && bytes[i] == '\r'
                && bytes[i + 1] == '\n'
                && bytes[i + 2] == '\r'
                && bytes[i + 3] == '\n') {
                return i + 4;
            }
            if (bytes[i] == '\n' && bytes[i + 1] == '\n') {
                return i + 2;
            }
        }
        return -1;
    }

    private static int findLineEnd(byte[] bytes, int offset) {
        for (int i = offset; i < bytes.length; i++) {
            if (bytes[i] == '\n') {
                return i > offset && bytes[i - 1] == '\r' ? i - 1 : i;
            }
        }
        return -1;
    }

    private static int indexOf(byte[] bytes, byte target, int start, int end) {
        for (int i = start; i < end; i++) {
            if (bytes[i] == target) {
                return i;
            }
        }
        return -1;
    }

    private static int digitValue(byte value) {
        return value >= '0' && value <= '9' ? value - '0' : -1;
    }

    private static boolean startsWith(byte[] bytes, int offset, byte[] prefix) {
        if (offset + prefix.length > bytes.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[offset + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
