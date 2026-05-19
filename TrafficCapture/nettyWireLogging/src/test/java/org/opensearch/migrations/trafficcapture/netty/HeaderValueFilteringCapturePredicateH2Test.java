package org.opensearch.migrations.trafficcapture.netty;

import java.util.function.Consumer;

import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Headers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * for {@link HeaderValueFilteringCapturePredicate#forH2Stream}.
 * Mirrors the H1 test cases for the same predicate and additionally locks in pseudo-header
 * mapping behavior.
 */
class HeaderValueFilteringCapturePredicateH2Test {

    private static Http2Headers h2(String method, String path) {
        return new DefaultHttp2Headers()
                .scheme("https")
                .authority("localhost")
                .method(method)
                .path(path);
    }

    @Test
    void suppressesByMethodPseudoHeader() {
        var headers = h2("GET", "/thing1/andThenSome");
        Assertions.assertEquals(RequestCapturePredicate.CaptureDirective.DROP,
                build(b -> b.methodPattern("GET")).forH2Stream(1, headers));
        Assertions.assertEquals(RequestCapturePredicate.CaptureDirective.CAPTURE,
                build(b -> b.methodPattern("POST")).forH2Stream(1, headers));
    }

    @Test
    void suppressesByPathPseudoHeader() {
        var headers = h2("GET", "/thing1/andThenSome");
        Assertions.assertEquals(RequestCapturePredicate.CaptureDirective.DROP,
                build(b -> b.pathPattern("/.*")).forH2Stream(1, headers));
        Assertions.assertEquals(RequestCapturePredicate.CaptureDirective.DROP,
                build(b -> b.pathPattern("/thing1/.*")).forH2Stream(1, headers));
        Assertions.assertEquals(RequestCapturePredicate.CaptureDirective.CAPTURE,
                build(b -> b.pathPattern("/_cat")).forH2Stream(1, headers));
    }

    @Test
    void suppressesByMethodAndPathCombo() {
        var headers = h2("GET", "/thing1/andThenSome");
        Assertions.assertEquals(RequestCapturePredicate.CaptureDirective.DROP,
                build(b -> b.methodAndPathPattern("GET /thing.*")).forH2Stream(1, headers));
        Assertions.assertEquals(RequestCapturePredicate.CaptureDirective.CAPTURE,
                build(b -> b.methodAndPathPattern("POST /thing.*")).forH2Stream(1, headers));
    }

    @Test
    void suppressesByRegularHeaderValue() {
        var headers = h2("GET", "/_search").add("x-amzn-request", "internal");
        Assertions.assertEquals(RequestCapturePredicate.CaptureDirective.DROP,
                build(b -> b.suppressCaptureHeaderPairs(java.util.Map.of("x-amzn-request", "internal")))
                        .forH2Stream(1, headers));
        Assertions.assertEquals(RequestCapturePredicate.CaptureDirective.CAPTURE,
                build(b -> b.suppressCaptureHeaderPairs(java.util.Map.of("x-amzn-request", "external")))
                        .forH2Stream(1, headers));
    }

    @Test
    void protocolPattern_isInert_forH2() {
        // Legacy "HTTP/2.*" pattern from the pre-RFC-0001 default is inert when called via forH2Stream.
        var headers = h2("GET", "/_cat");
        Assertions.assertEquals(RequestCapturePredicate.CaptureDirective.CAPTURE,
                build(b -> b.protocolPattern("HTTP/2.*")).forH2Stream(1, headers),
                "protocolPattern only applies to H1 protocolVersion text and must not affect H2 streams");
    }

    @Test
    void nullHeaders_returnsCapture() {
        Assertions.assertEquals(RequestCapturePredicate.CaptureDirective.CAPTURE,
                build(b -> b.methodPattern("GET")).forH2Stream(1, null),
                "null headers (defensive path) should not throw and should default to CAPTURE");
    }

    private static HeaderValueFilteringCapturePredicate
    build(Consumer<HeaderValueFilteringCapturePredicate.HeaderValueFilteringCapturePredicateBuilder> filler) {
        var b = HeaderValueFilteringCapturePredicate.builder();
        filler.accept(b);
        return b.build();
    }
}
