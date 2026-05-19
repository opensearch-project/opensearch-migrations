package org.opensearch.migrations.replay.datahandlers.http;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.H2Accumulation;
import org.opensearch.migrations.replay.TestCapturePacketToHttpHandler;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.trafficcapture.protos.Http2HeaderField;
import org.opensearch.migrations.transform.TransformationLoader;

import com.google.protobuf.ByteString;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequestEncoder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * — cross-protocol regression: an H2-shaped request, when run through the
 * H2ToH1ObjectAdapter and serialized back to bytes via Netty's HttpRequestEncoder, must
 * be processed by the existing HttpJsonTransformingConsumer pipeline byte-for-byte
 * identically to how an equivalent H1 request would be processed.
 *
 * <p>This proves the boundary adapter is a clean architectural seam: the transformation
 * pipeline is protocol-agnostic by virtue of receiving HttpObjects only.
 */
class CrossProtocolTransformerRegressionTest extends InstrumentationTest {

    private static H2Accumulation.StreamState newRequestStream(int streamId, String method, String path,
                                                                String authority, byte[] body,
                                                                List<Http2HeaderField> regularHeaders) {
        var s = new H2Accumulation.StreamState(streamId);
        s.getRequestPseudoHeaders().put(":method", Unpooled.copiedBuffer(method.getBytes(StandardCharsets.UTF_8)));
        s.getRequestPseudoHeaders().put(":path", Unpooled.copiedBuffer(path.getBytes(StandardCharsets.UTF_8)));
        s.getRequestPseudoHeaders().put(":scheme", Unpooled.copiedBuffer("https".getBytes(StandardCharsets.UTF_8)));
        s.getRequestPseudoHeaders().put(":authority", Unpooled.copiedBuffer(authority.getBytes(StandardCharsets.UTF_8)));
        if (regularHeaders != null) s.getRequestHeaderFields().addAll(regularHeaders);
        if (body != null && body.length > 0) {
            s.getRequestBody().add(Unpooled.wrappedBuffer(body));
        }
        return s;
    }

    /**
     * Serialize H1 HttpObjects to wire bytes via an {@link EmbeddedChannel} containing
     * {@link HttpRequestEncoder}. Mirrors what a real H1 client / Netty pipeline would put
     * on the wire, so the transformer's input is byte-identical to the H1 case.
     */
    private static byte[] serializeH1Objects(List<HttpObject> objects) {
        var ec = new EmbeddedChannel(new HttpRequestEncoder());
        try {
            for (var o : objects) {
                ec.writeOutbound(o);
            }
            ec.flushOutbound();
            var out = new ArrayList<byte[]>();
            int total = 0;
            Object next;
            while ((next = ec.readOutbound()) != null) {
                var buf = (ByteBuf) next;
                var arr = new byte[buf.readableBytes()];
                buf.readBytes(arr);
                buf.release();
                out.add(arr);
                total += arr.length;
            }
            var combined = new byte[total];
            int off = 0;
            for (var a : out) {
                System.arraycopy(a, 0, combined, off, a.length);
                off += a.length;
            }
            return combined;
        } finally {
            ec.finishAndReleaseAll();
        }
    }

    @Test
    void h2DerivedRequest_runsThroughTransformer_withSkippedStatus() throws Exception {
        var stream = newRequestStream(1, "POST", "/_bulk", "foo.example",
                "{\"index\":{}}\n{\"f\":1}\n".getBytes(StandardCharsets.UTF_8),
                List.of(Http2HeaderField.newBuilder()
                        .setName(ByteString.copyFromUtf8("content-type"))
                        .setValue(ByteString.copyFromUtf8("application/x-ndjson"))
                        .build()));

        var objects = H2ToH1ObjectAdapter.toH1RequestObjects(stream);
        var wireBytes = serializeH1Objects(objects);
        // Sanity: the wire bytes look like an H1 request line.
        var wirePreview = new String(wireBytes, 0, Math.min(40, wireBytes.length), StandardCharsets.UTF_8);
        Assertions.assertTrue(wirePreview.startsWith("POST /_bulk HTTP/1.1"),
                "wire bytes must begin with H1 request line, was: " + wirePreview);
        Assertions.assertTrue(new String(wireBytes, StandardCharsets.UTF_8).contains("host: foo.example"),
                "Host: header must be set from :authority pseudo-header");

        // Feed the bytes into the transformer with a no-op transformation; expect SKIPPED.
        var dummyResponse = new AggregatedRawResponse(null, 17, Duration.ZERO, List.of(), null);
        var capture = new TestCapturePacketToHttpHandler(Duration.ofMillis(10), dummyResponse);
        var transformer = new HttpJsonTransformingConsumer<>(
                new TransformationLoader().getTransformerFactoryLoaderWithNewHostName(null),
                null,
                capture,
                rootContext.getTestConnectionRequestContext(0));
        transformer.consumeBytes(wireBytes);
        var result = transformer.finalizeRequest().get();
        Assertions.assertEquals(HttpRequestTransformationStatus.skipped(), result.transformationStatus,
                "no-op transformation must be skipped status");
        Assertions.assertArrayEquals(wireBytes, capture.getBytesCaptured(),
                "transformer must round-trip H2-derived bytes byte-identically");
    }

    @Test
    void h2DerivedRequest_runsThroughHostRewriteTransformer() throws Exception {
        var stream = newRequestStream(1, "GET", "/_search", "foo.example", new byte[0], List.of());

        var objects = H2ToH1ObjectAdapter.toH1RequestObjects(stream);
        var wireBytes = serializeH1Objects(objects);
        Assertions.assertTrue(new String(wireBytes, StandardCharsets.UTF_8).contains("host: foo.example"));

        var dummyResponse = new AggregatedRawResponse(null, 17, Duration.ZERO, List.of(), null);
        var capture = new TestCapturePacketToHttpHandler(Duration.ofMillis(10), dummyResponse);
        var transformer = new HttpJsonTransformingConsumer<>(
                new TransformationLoader().getTransformerFactoryLoaderWithNewHostName("bar.example"),
                null,
                capture,
                rootContext.getTestConnectionRequestContext(0));
        transformer.consumeBytes(wireBytes);
        var result = transformer.finalizeRequest().get();
        Assertions.assertEquals(HttpRequestTransformationStatus.completed(), result.transformationStatus,
                "host rewrite transformer must mark status COMPLETED");
        var captured = capture.getCapturedAsString();
        Assertions.assertTrue(captured.contains("host: bar.example"),
                "host header must be rewritten to bar.example, was: " + captured);
        Assertions.assertFalse(captured.contains("foo.example"),
                "no traces of original host should remain in transformed bytes");
    }
}
