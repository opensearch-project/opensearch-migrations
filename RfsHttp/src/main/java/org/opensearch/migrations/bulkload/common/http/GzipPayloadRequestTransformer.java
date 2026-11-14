package org.opensearch.migrations.bulkload.common.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

import io.netty.handler.codec.http.HttpHeaderNames;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * The {@code GzipPayloadRequestTransformer} class implements the {@link RequestTransformer} interface and
 * provides functionality to transform HTTP request payloads by applying GZIP compression if needed.
 *
 * <p>This class checks the request headers to determine if the payload should be compressed using GZIP.
 * If the "Content-Encoding" header is set to "gzip" and the payload is not already compressed, the
 * payload is compressed using GZIP. Otherwise, the payload remains unchanged.</p>
 *
 * @see RequestTransformer
 */
@AllArgsConstructor
@Slf4j
public class GzipPayloadRequestTransformer implements RequestTransformer {
    public static final String CONTENT_ENCODING_HEADER_NAME = HttpHeaderNames.CONTENT_ENCODING.toString();
    public static final String GZIP_CONTENT_ENCODING_HEADER_VALUE = "gzip";

    private static final int READ_BUFFER_SIZE = 256 * 1024;  // Arbitrary, 256KB

    // Local benchmarks show 15% throughput improvement with this setting
    private static final int COMPRESSION_LEVEL = Deflater.BEST_SPEED;

    private static final int GZIP_MAGIC_NUMBER = 0x8b1f; // 0x1F8B in little-endian for gzip starting bytes

    private static boolean headersUseGzipContentEncoding(final Map<String, List<String>> headers) {
        return headers.getOrDefault(CONTENT_ENCODING_HEADER_NAME, List.of())
            .contains(GZIP_CONTENT_ENCODING_HEADER_VALUE);
    }

    private static boolean isGzipped(ByteBuffer buffer) {
        if (buffer == null || buffer.remaining() < 2) {
            return false;
        }

        // Mark the current position so we can reset after reading
        buffer.mark();

        // Read the first two bytes
        byte firstByte = buffer.get();
        byte secondByte = buffer.get();

        // Reset the buffer to its original position
        buffer.reset();

        int magic = Byte.toUnsignedInt(firstByte) | (Byte.toUnsignedInt(secondByte) << 8);
        return magic == GZIP_MAGIC_NUMBER;
    }

    @Override
    public Mono<TransformedRequest> transform(
        String method, String path, Map<String, List<String>> headers, Mono<ByteBuffer> body
    ) {
        return Mono.just(new TransformedRequest(new HashMap<>(headers),
            body.map(bodyBuf -> (headersUseGzipContentEncoding(headers) && !isGzipped(bodyBuf)) ? gzipByteBufferSimple(
                bodyBuf) : bodyBuf)
        ));
    }

    /**
     * Gzip {@code body} the way this transformer would. A caller that compresses first knows the size it sends;
     * the transformer passes an already gzipped body through unchanged.
     */
    public static byte[] gzip(byte[] body) {
        return gzip(ByteBuffer.wrap(body));
    }

    /** Compress the remaining bytes without first copying them into a byte array. */
    public static byte[] gzip(ByteBuffer body) {
        return gzip(new ByteBuffer[] {body});
    }

    /** Compress several contiguous body regions as one gzip stream, without joining them first. */
    public static byte[] gzip(ByteBuffer[] bodies) {
        return gzipByteBuffers(bodies).array();
    }

    @SneakyThrows
    private static ByteBuffer gzipByteBufferSimple(final ByteBuffer inputBuffer) {
        return gzipByteBuffers(new ByteBuffer[] {inputBuffer});
    }

    @SneakyThrows
    private static ByteBuffer gzipByteBuffers(final ByteBuffer[] inputBuffers) {
        long inputBytes = 0;
        var baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOutputStream = new FastGzipOutputStream(baos, READ_BUFFER_SIZE, false)) {
            byte[] transferBuffer = null;
            for (var inputBuffer : inputBuffers) {
                var readBuffer = inputBuffer.duplicate();
                inputBytes += readBuffer.remaining();
                if (readBuffer.hasArray()) {
                    gzipOutputStream.write(readBuffer.array(),
                        readBuffer.arrayOffset() + readBuffer.position(),
                        readBuffer.remaining()
                    );
                } else {
                    if (transferBuffer == null) {
                        transferBuffer = new byte[READ_BUFFER_SIZE];
                    }
                    while (readBuffer.hasRemaining()) {
                        int bytesRead = Math.min(transferBuffer.length, readBuffer.remaining());
                        readBuffer.get(transferBuffer, 0, bytesRead);
                        gzipOutputStream.write(transferBuffer, 0, bytesRead);
                    }
                }
            }
        }
        if (inputBytes > 0) {
            long uncompressedBytes = inputBytes;
            log.atDebug().setMessage("Gzip compression ratio: {}")
                .addArgument(() -> String.format("%.2f%%", (double) baos.size() / uncompressedBytes * 100)).log();
        }
        return ByteBuffer.wrap(baos.toByteArray());
    }

    private static class FastGzipOutputStream extends GZIPOutputStream {
        public FastGzipOutputStream(OutputStream out, int size, boolean syncFlush) throws IOException {
            super(out, size, syncFlush);
            def.setLevel(COMPRESSION_LEVEL);
        }
    }
}
