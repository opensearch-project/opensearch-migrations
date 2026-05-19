package org.opensearch.migrations.replay.datahandlers.http;

import java.util.ArrayList;
import java.util.List;

import org.opensearch.migrations.replay.H2Accumulation;
import org.opensearch.migrations.trafficcapture.protos.Http2HeaderField;

import com.google.protobuf.ByteString;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * RFC 0001 §8.4 — coverage for {@link H2ToH1ObjectAdapter}. One test per row of the
 * pseudo-header / forbidden-header mapping table, plus end-to-end conversion.
 */
class H2ToH1ObjectAdapterTest {

    private static H2Accumulation.StreamState newStreamState(int streamId) {
        return new H2Accumulation.StreamState(streamId);
    }

    private static void putRequestPseudo(H2Accumulation.StreamState s, String name, String value) {
        s.requestPseudoHeaders.put(name, Unpooled.copiedBuffer(value.getBytes()));
    }

    private static void putResponsePseudo(H2Accumulation.StreamState s, String name, String value) {
        s.responsePseudoHeaders.put(name, Unpooled.copiedBuffer(value.getBytes()));
    }

    private static Http2HeaderField hf(String name, String value) {
        return Http2HeaderField.newBuilder()
                .setName(ByteString.copyFromUtf8(name))
                .setValue(ByteString.copyFromUtf8(value))
                .build();
    }

    @Test
    void minimalGetRequest_mapsToH1Request() {
        var s = newStreamState(1);
        putRequestPseudo(s, ":method", "GET");
        putRequestPseudo(s, ":path", "/_search");
        putRequestPseudo(s, ":scheme", "https");
        putRequestPseudo(s, ":authority", "localhost:9200");

        var objects = H2ToH1ObjectAdapter.toH1RequestObjects(s);
        Assertions.assertTrue(objects.get(0) instanceof DefaultHttpRequest);
        var req = (DefaultHttpRequest) objects.get(0);
        Assertions.assertEquals(HttpVersion.HTTP_1_1, req.protocolVersion());
        Assertions.assertEquals(HttpMethod.GET, req.method());
        Assertions.assertEquals("/_search", req.uri());
        Assertions.assertEquals("localhost:9200", req.headers().get(HttpHeaderNames.HOST));
        Assertions.assertTrue(objects.get(objects.size() - 1) instanceof DefaultLastHttpContent);
    }

    @Test
    void postWithBody_mapsToHttpContentSequence() {
        var s = newStreamState(3);
        putRequestPseudo(s, ":method", "POST");
        putRequestPseudo(s, ":path", "/_bulk");
        putRequestPseudo(s, ":authority", "localhost");
        s.requestBody.add(Unpooled.wrappedBuffer("chunk-A".getBytes()));
        s.requestBody.add(Unpooled.wrappedBuffer("chunk-B".getBytes()));
        s.requestBody.add(Unpooled.wrappedBuffer("chunk-C".getBytes()));

        var objects = H2ToH1ObjectAdapter.toH1RequestObjects(s);
        Assertions.assertEquals(4, objects.size(),
                "request line + 2 HttpContent + 1 LastHttpContent");
        Assertions.assertTrue(objects.get(0) instanceof DefaultHttpRequest);
        Assertions.assertTrue(objects.get(1) instanceof DefaultHttpContent);
        Assertions.assertTrue(objects.get(2) instanceof DefaultHttpContent);
        Assertions.assertTrue(objects.get(3) instanceof DefaultLastHttpContent);

        // Release the retained slices so PARANOID leak detection stays clean.
        for (var o : objects) {
            if (o instanceof io.netty.util.ReferenceCounted rc) rc.release();
        }
    }

    @Test
    void missingMethod_throwsMalformed() {
        var s = newStreamState(1);
        putRequestPseudo(s, ":path", "/_search");
        Assertions.assertThrows(H2ToH1ObjectAdapter.MalformedH2RequestException.class,
                () -> H2ToH1ObjectAdapter.toH1RequestObjects(s));
    }

    @Test
    void missingPath_onNonConnectMethod_throwsMalformed() {
        var s = newStreamState(1);
        putRequestPseudo(s, ":method", "GET");
        Assertions.assertThrows(H2ToH1ObjectAdapter.MalformedH2RequestException.class,
                () -> H2ToH1ObjectAdapter.toH1RequestObjects(s));
    }

    @Test
    void connectMethod_withPath_throwsMalformed() {
        var s = newStreamState(1);
        putRequestPseudo(s, ":method", "CONNECT");
        putRequestPseudo(s, ":path", "/forbidden");
        putRequestPseudo(s, ":authority", "host:443");
        Assertions.assertThrows(H2ToH1ObjectAdapter.MalformedH2RequestException.class,
                () -> H2ToH1ObjectAdapter.toH1RequestObjects(s));
    }

    @Test
    void crlfInHeaderValue_throwsMalformed() {
        var s = newStreamState(1);
        putRequestPseudo(s, ":method", "GET");
        putRequestPseudo(s, ":path", "/");
        s.requestHeaderFields.add(hf("x-injection", "value\r\nset-cookie: bad"));
        Assertions.assertThrows(H2ToH1ObjectAdapter.MalformedH2RequestException.class,
                () -> H2ToH1ObjectAdapter.toH1RequestObjects(s));
    }

    @Test
    void connectionSpecificHeaders_areDropped() {
        var s = newStreamState(1);
        putRequestPseudo(s, ":method", "GET");
        putRequestPseudo(s, ":path", "/");
        s.requestHeaderFields.add(hf("connection", "keep-alive"));
        s.requestHeaderFields.add(hf("keep-alive", "timeout=5"));
        s.requestHeaderFields.add(hf("proxy-connection", "close"));
        s.requestHeaderFields.add(hf("upgrade", "h2c"));
        s.requestHeaderFields.add(hf("x-traceable", "yes"));

        var objects = H2ToH1ObjectAdapter.toH1RequestObjects(s);
        var req = (DefaultHttpRequest) objects.get(0);
        Assertions.assertNull(req.headers().get("connection"));
        Assertions.assertNull(req.headers().get("keep-alive"));
        Assertions.assertNull(req.headers().get("proxy-connection"));
        Assertions.assertNull(req.headers().get("upgrade"));
        Assertions.assertEquals("yes", req.headers().get("x-traceable"));
    }

    @Test
    void chunkedTransferEncoding_isDropped_otherTeIsPreserved() {
        var s = newStreamState(1);
        putRequestPseudo(s, ":method", "GET");
        putRequestPseudo(s, ":path", "/");
        s.requestHeaderFields.add(hf("transfer-encoding", "chunked"));
        s.requestHeaderFields.add(hf("transfer-encoding", "identity"));
        var objects = H2ToH1ObjectAdapter.toH1RequestObjects(s);
        var req = (DefaultHttpRequest) objects.get(0);
        var values = req.headers().getAll("transfer-encoding");
        Assertions.assertEquals(1, values.size());
        Assertions.assertEquals("identity", values.get(0));
    }

    @Test
    void teTrailers_isPreserved_otherTeIsDropped() {
        var s = newStreamState(1);
        putRequestPseudo(s, ":method", "GET");
        putRequestPseudo(s, ":path", "/");
        s.requestHeaderFields.add(hf("te", "trailers"));
        s.requestHeaderFields.add(hf("te", "gzip"));
        var objects = H2ToH1ObjectAdapter.toH1RequestObjects(s);
        var req = (DefaultHttpRequest) objects.get(0);
        var values = req.headers().getAll("te");
        Assertions.assertEquals(1, values.size());
        Assertions.assertEquals("trailers", values.get(0));
    }

    @Test
    void cookieCrumbs_areFolded() {
        var s = newStreamState(1);
        putRequestPseudo(s, ":method", "GET");
        putRequestPseudo(s, ":path", "/");
        s.requestHeaderFields.add(hf("cookie", "a=1"));
        s.requestHeaderFields.add(hf("cookie", "b=2"));
        s.requestHeaderFields.add(hf("cookie", "c=3"));
        var objects = H2ToH1ObjectAdapter.toH1RequestObjects(s);
        var req = (DefaultHttpRequest) objects.get(0);
        Assertions.assertEquals("a=1; b=2; c=3", req.headers().get(HttpHeaderNames.COOKIE));
    }

    @Test
    void hostHeader_isDropped_whenAuthorityPresent() {
        var s = newStreamState(1);
        putRequestPseudo(s, ":method", "GET");
        putRequestPseudo(s, ":path", "/");
        putRequestPseudo(s, ":authority", "real-authority.example");
        s.requestHeaderFields.add(hf("host", "fake-host.example"));
        var objects = H2ToH1ObjectAdapter.toH1RequestObjects(s);
        var req = (DefaultHttpRequest) objects.get(0);
        Assertions.assertEquals("real-authority.example", req.headers().get(HttpHeaderNames.HOST));
        var hosts = req.headers().getAll(HttpHeaderNames.HOST);
        Assertions.assertEquals(1, hosts.size(), "Host must appear exactly once");
    }

    @Test
    void responseStatusPseudoHeader_mapsToStatusLine() {
        var s = newStreamState(1);
        putResponsePseudo(s, ":status", "204");
        var objects = H2ToH1ObjectAdapter.toH1ResponseObjects(s);
        var resp = (DefaultHttpResponse) objects.get(0);
        Assertions.assertEquals(HttpResponseStatus.NO_CONTENT, resp.status());
    }

    @Test
    void responseMissingStatus_throwsMalformed() {
        var s = newStreamState(1);
        Assertions.assertThrows(H2ToH1ObjectAdapter.MalformedH2RequestException.class,
                () -> H2ToH1ObjectAdapter.toH1ResponseObjects(s));
    }

    @Test
    void requestTrailers_attachToLastHttpContent() {
        var s = newStreamState(1);
        putRequestPseudo(s, ":method", "POST");
        putRequestPseudo(s, ":path", "/_bulk");
        s.requestBody.add(Unpooled.wrappedBuffer("body".getBytes()));
        s.requestTrailers = new ArrayList<>(List.of(
                hf("x-trailer", "after-body"),
                hf("Trailer-Mixed-Case", "preserved-as-lowercase")));

        var objects = H2ToH1ObjectAdapter.toH1RequestObjects(s);
        var last = (DefaultLastHttpContent) objects.get(objects.size() - 1);
        Assertions.assertEquals("after-body", last.trailingHeaders().get("x-trailer"));
        Assertions.assertEquals("preserved-as-lowercase",
                last.trailingHeaders().get("trailer-mixed-case"));

        for (var o : objects) {
            if (o instanceof io.netty.util.ReferenceCounted rc) rc.release();
        }
    }
}
