package org.opensearch.migrations.trafficcapture.netty;

import java.util.function.Consumer;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HeaderValueFilteringCapturePredicateTest {
    @Test
    public void suppresses() {
        var req = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/thing1/andThenSome", new DefaultHttpHeaders());
        Assertions.assertEquals(RequestCapturePredicate.CaptureDirective.DROP,
            build(b->b.methodPattern("GET")).apply(req));
        Assertions.assertEquals(RequestCapturePredicate.CaptureDirective.CAPTURE,
            build(b->b.methodPattern("POST")).apply(req));

        Assertions.assertEquals(RequestCapturePredicate.CaptureDirective.DROP,
            build(b->b.pathPattern("/.*")).apply(req));
        Assertions.assertEquals(RequestCapturePredicate.CaptureDirective.DROP,
            build(b->b.pathPattern("/thing1/.*")).apply(req));
        Assertions.assertEquals(RequestCapturePredicate.CaptureDirective.CAPTURE,
            build(b->b.pathPattern("/_cat")).apply(req));

        Assertions.assertEquals(RequestCapturePredicate.CaptureDirective.DROP,
            build(b->b.protocolPattern("HTTP/1\\.0")).apply(
                new DefaultHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.GET, "/", new DefaultHttpHeaders())
            ));
        Assertions.assertEquals(RequestCapturePredicate.CaptureDirective.CAPTURE,
            build(b->b.protocolPattern("HTTP/1.0")).apply(req));

        Assertions.assertEquals(RequestCapturePredicate.CaptureDirective.DROP,
            build(b->b.methodAndPathPattern("GET /thing.*")).apply(req));
        Assertions.assertEquals(RequestCapturePredicate.CaptureDirective.CAPTURE,
            build(b->b.methodAndPathPattern("POST /thing/.*")).apply(req));
    }

    private static HeaderValueFilteringCapturePredicate
    build(Consumer<HeaderValueFilteringCapturePredicate.HeaderValueFilteringCapturePredicateBuilder> filler) {
        var b = HeaderValueFilteringCapturePredicate.builder();
        filler.accept(b);
        return b.build();
    }
}
