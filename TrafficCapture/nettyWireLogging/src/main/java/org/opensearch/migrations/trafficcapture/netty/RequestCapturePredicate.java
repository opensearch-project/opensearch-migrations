package org.opensearch.migrations.trafficcapture.netty;

import java.util.function.Function;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http2.Http2Headers;
import lombok.Getter;

public class RequestCapturePredicate
        implements Function<HttpRequest, RequestCapturePredicate.CaptureDirective>, ICaptureDecisionPolicy {

    public enum CaptureDirective {
        CAPTURE,
        DROP
    }

    @Getter
    protected final PassThruHttpHeaders.HttpHeadersToPreserve headersRequiredForMatcher;

    public RequestCapturePredicate() {
        this(new PassThruHttpHeaders.HttpHeadersToPreserve());
    }

    public RequestCapturePredicate(PassThruHttpHeaders.HttpHeadersToPreserve incoming) {
        this.headersRequiredForMatcher = incoming;
    }

    @Override
    public CaptureDirective apply(HttpRequest request) {
        return CaptureDirective.CAPTURE;
    }

    /** {@inheritDoc} — H1 path of the unified policy interface. Delegates to {@link #apply}. */
    @Override
    public CaptureDirective forH1Request(HttpRequest request) {
        return apply(request);
    }

    /**
     * H2 path of the unified policy interface. Default returns CAPTURE; subclasses override
     * to evaluate against pseudo-headers (RFC 7540 §8.1.2.3) and regular headers.
     */
    @Override
    public CaptureDirective forH2Stream(int streamId, Http2Headers headers) {
        return CaptureDirective.CAPTURE;
    }
}
