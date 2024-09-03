package com.rfs.common.http;

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

@AllArgsConstructor
@Slf4j
public class GzipRequestTransformer implements RequestTransformer {
    private static final String CONTENT_ENCODING_HEADER_NAME = HttpHeaderNames.CONTENT_ENCODING.toString();
    private static final String GZIP_CONTENT_ENCODING_HEADER_VALUE = "gzip";
    private static final int READ_BUFFER_SIZE = 256 * 1024;  // Arbitrary, 256KB

    // Local benchmarks show 15% throughput improvement with this setting
    private static final int COMPRESSION_LEVEL = Deflater.BEST_SPEED;

    @Override
    public Mono<TransformedRequest> transform(
        String method,
        String path,
        Map<String, List<String>> headers,
        Mono<ByteBuffer> body
    ) {
        return body.map(this::gzipByteBufferSimple).singleOptional().flatMap(bodyOp -> {
            Map<String, List<String>> newHeaders = new HashMap<>(headers);
            if (bodyOp.isPresent()) {
                newHeaders.put(CONTENT_ENCODING_HEADER_NAME, List.of(GZIP_CONTENT_ENCODING_HEADER_VALUE));
            }
            return Mono.just(new TransformedRequest(newHeaders, Mono.justOrEmpty(bodyOp)));
        });
    }

    @SneakyThrows
    private ByteBuffer gzipByteBufferSimple(final ByteBuffer inputBuffer) {
        var readbuffer = inputBuffer.duplicate();
        var baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOutputStream = new FastGzipOutputStream(baos, READ_BUFFER_SIZE, false)) {
            if (readbuffer.hasArray()) {
                gzipOutputStream.write(
                    readbuffer.array(),
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
            log.atDebug()
                .setMessage("Gzip compression ratio: {}")
                .addArgument(() -> String.format("%.2f%%", (double) baos.size() / inputBuffer.remaining() * 100))
                .log();
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
