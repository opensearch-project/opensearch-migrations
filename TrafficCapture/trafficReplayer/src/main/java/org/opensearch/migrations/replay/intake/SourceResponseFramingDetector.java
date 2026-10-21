/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.intake;

import java.util.Locale;

import org.opensearch.migrations.replay.SourceResponseFramingFallbackReason;
import org.opensearch.migrations.replay.netty.InterimHttpResponseHandler;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import lombok.NonNull;

/**
 * Incrementally recognizes the terminal boundary of one captured final HTTP response.
 *
 * <p>The detector consumes the same byte arrays retained by source-response accumulation but never
 * aggregates a second response. Decoded HTTP objects are released immediately. Netty retains only
 * incomplete framing syntax that spans inputs.</p>
 *
 * <p>Failure is conservative: malformed or unsupported framing enters {@link Status#FALLBACK}
 * instead of claiming an early response boundary. The caller can then retain the historical
 * next-request and connection-boundary behavior.</p>
 */
final class SourceResponseFramingDetector implements AutoCloseable {

    enum Status {
        IN_PROGRESS,
        COMPLETE,
        FALLBACK
    }

    record Result(Status status, SourceResponseFramingFallbackReason fallbackReason) {
        Result {
            if ((status == Status.FALLBACK) != (fallbackReason != null)) {
                throw new IllegalArgumentException("only a fallback result carries a fallback reason");
            }
        }
    }

    private final boolean connectRequest;
    private final EmbeddedChannel decoderChannel;
    private Status status = Status.IN_PROGRESS;
    private SourceResponseFramingFallbackReason fallbackReason;
    private Throwable failureCause;
    private boolean suppressDecoderTransitions;
    private boolean channelDisposed;

    SourceResponseFramingDetector(@NonNull String requestMethod) {
        var normalizedMethod = requestMethod.trim().toUpperCase(Locale.ROOT);
        var headRequest = "HEAD".equals(normalizedMethod);
        connectRequest = "CONNECT".equals(normalizedMethod);
        decoderChannel = new EmbeddedChannel(
            new RequestMethodAwareHttpResponseDecoder(headRequest),
            new InterimHttpResponseHandler(),
            new ResponseBoundaryHandler()
        );
    }

    Result accept(@NonNull byte[] bytes) {
        if (status != Status.IN_PROGRESS) {
            throw new IllegalStateException("response framing is already " + status);
        }
        if (bytes.length == 0) {
            return result();
        }

        try {
            decoderChannel.writeInbound(Unpooled.wrappedBuffer(bytes));
            decoderChannel.checkException();
        } catch (Throwable failure) {
            enterFallback(SourceResponseFramingFallbackReason.DECODER_FAILURE, failure);
        }

        if (status != Status.IN_PROGRESS) {
            disposeChannel();
        }
        return result();
    }

    Result result() {
        return new Result(status, fallbackReason);
    }

    Throwable failureCause() {
        return failureCause;
    }

    @Override
    public void close() {
        disposeChannel();
    }

    private void complete() {
        if (!suppressDecoderTransitions && status == Status.IN_PROGRESS) {
            status = Status.COMPLETE;
        }
    }

    private void enterFallback(SourceResponseFramingFallbackReason reason, Throwable failure) {
        if (suppressDecoderTransitions || status == Status.FALLBACK) {
            return;
        }
        status = Status.FALLBACK;
        fallbackReason = reason;
        if (failureCause == null) {
            failureCause = failure;
        }
    }

    private void disposeChannel() {
        if (channelDisposed) {
            return;
        }
        channelDisposed = true;
        suppressDecoderTransitions = true;
        try {
            decoderChannel.finishAndReleaseAll();
        } catch (Throwable disposalFailure) {
            if (failureCause == null) {
                failureCause = disposalFailure;
            } else if (failureCause != disposalFailure) {
                failureCause.addSuppressed(disposalFailure);
            }
        }
    }

    private final class ResponseBoundaryHandler extends ChannelInboundHandlerAdapter {
        private boolean finalResponseStarted;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                if (status == Status.COMPLETE) {
                    return;
                }
                if (msg instanceof HttpObject httpObject && httpObject.decoderResult().isFailure()) {
                    enterFallback(
                        SourceResponseFramingFallbackReason.MALFORMED_RESPONSE,
                        httpObject.decoderResult().cause()
                    );
                    return;
                }

                if (msg instanceof HttpResponse response) {
                    if (finalResponseStarted || status == Status.COMPLETE) {
                        enterFallback(
                            SourceResponseFramingFallbackReason.MALFORMED_RESPONSE,
                            new IllegalStateException("multiple final responses were decoded")
                        );
                        return;
                    }
                    var statusCode = response.status().code();
                    if (statusCode == 101 || (connectRequest && statusCode >= 200 && statusCode < 300)) {
                        enterFallback(SourceResponseFramingFallbackReason.UNSUPPORTED_UPGRADE, null);
                        return;
                    }
                    finalResponseStarted = true;
                }

                if (msg instanceof LastHttpContent) {
                    if (!finalResponseStarted) {
                        enterFallback(
                            SourceResponseFramingFallbackReason.MALFORMED_RESPONSE,
                            new IllegalStateException("response content ended before a final response began")
                        );
                        return;
                    }
                    complete();
                }
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }
    }

    private static final class RequestMethodAwareHttpResponseDecoder extends HttpResponseDecoder {
        private final boolean headRequest;

        private RequestMethodAwareHttpResponseDecoder(boolean headRequest) {
            this.headRequest = headRequest;
        }

        @Override
        protected boolean isContentAlwaysEmpty(HttpMessage message) {
            return headRequest || super.isContentAlwaysEmpty(message);
        }
    }
}
