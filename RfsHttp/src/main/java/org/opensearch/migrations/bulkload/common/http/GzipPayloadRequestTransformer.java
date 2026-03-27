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

    @SneakyThrows
    private ByteBuffer gzipByteBufferSimple(final ByteBuffer inputBuffer) {
        var readbuffer = inputBuffer.duplicate();
        var baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOutputStream = new FastGzipOutputStream(baos, READ_BUFFER_SIZE, false)) {
            if (readbuffer.hasArray()) {
                gzipOutputStream.write(readbuffer.array(),
                    readbuffer.arrayOffset() + readbuffer.position(),
                    readbuffer.remaining()
                );
            } else {
                byte[] buffer = new byte[READ_BUFFER_SIZE];
                while (readbuffer.hasRemaining()) {
                    int bytesRead = Math.min(buffer.length, readbuffer.remaining());
                    readbuffer.get(buffer, 0, bytesRead);
                    gzipOutputStream.write(buffer, 0, bytesRead);
                }
            }
        }
        if (inputBuffer.remaining() > 0) {
            log.atDebug().setMessage("Gzip compression ratio: {}")
                .addArgument(() -> String.format("%.2f%%", (double) baos.size() / inputBuffer.remaining() * 100)).log();
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
