package org.opensearch.migrations.trafficcapture.netty;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import lombok.Getter;

import java.util.function.Function;

public class RequestCapturePredicate implements Function<HttpRequest, RequestCapturePredicate.CaptureDirective> {

    public enum CaptureDirective {
        CAPTURE, DROP
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
}
