package org.opensearch.migrations.trafficcapture.netty;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http2.Http2Headers;

/**
 * Capture decision policy: evaluates per-request whether the proxy should capture, drop, or
 * otherwise treat traffic. Two flavors of input — the existing H1 path provides
 * {@link HttpRequest}, the H2 path provides a streamId + decoded
 * {@link Http2Headers}.
 *
 * <p>Implementations should be stateless and thread-safe; they are called from the Netty event
 * loop and may be shared across connections.
 */
public interface ICaptureDecisionPolicy {

    /** Capture decision for a single H1 request. */
    RequestCapturePredicate.CaptureDirective forH1Request(HttpRequest request);

    /**
     * Capture decision for a single H2 stream, given the decoded HEADERS frame fields.
     * Default implementation always returns {@link RequestCapturePredicate.CaptureDirective#CAPTURE}
     * \u2014 H2 captures pass through unless an implementation overrides.
     */
    default RequestCapturePredicate.CaptureDirective forH2Stream(int streamId, Http2Headers headers) {
        return RequestCapturePredicate.CaptureDirective.CAPTURE;
    }
}
