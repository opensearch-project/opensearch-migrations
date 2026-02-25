package org.opensearch.migrations.transform.shim.netty;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.opensearch.migrations.IHttpMessage;
import org.opensearch.migrations.aws.SigV4Signer;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

/**
 * Pipeline handler that applies SigV4 signing to outbound requests.
 * Uses SigV4Signer from awsUtilities — the same signing implementation
 * used by the replayer's NettyJsonContentAuthSigner + SigV4AuthTransformerFactory.
 *
 * Pipeline position: after RequestTransformHandler, before BackendForwardingHandler.
 */
@Slf4j
public class SigV4SigningHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private final AwsCredentialsProvider credentialsProvider;
    private final String service;
    private final String region;
    private final String protocol;

    public SigV4SigningHandler(AwsCredentialsProvider credentialsProvider,
                               String service, String region, String protocol) {
        super(false); // don't auto-release — we pass the request downstream
        this.credentialsProvider = credentialsProvider;
        this.service = service;
        this.region = region;
        this.protocol = protocol;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        try {
            var signer = new SigV4Signer(credentialsProvider, service, region, protocol, null);

            // Feed body content to signer
            if (request.content().readableBytes() > 0) {
                signer.consumeNextPayloadPart(request.content().nioBuffer());
            }

            // Finalize signature
            var signatureHeaders = signer.finalizeSignature(toHttpMessage(request));

            // Apply auth headers to the request
            for (var entry : signatureHeaders.entrySet()) {
                request.headers().remove(entry.getKey());
                for (var value : entry.getValue()) {
                    request.headers().add(entry.getKey(), value);
                }
            }

            log.debug("SigV4 signed request: {} {}", request.method(), request.uri());
            ctx.fireChannelRead(request);
        } catch (RuntimeException e) {
            request.release();
            log.error("SigV4 signing failed", e);
            RequestTransformHandler.sendError(ctx,
                io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR,
                "SigV4 signing failed",
                Boolean.TRUE.equals(ctx.channel().attr(ShimChannelAttributes.KEEP_ALIVE).get()));
        }
    }

    /** Adapt a Netty FullHttpRequest to the IHttpMessage interface used by SigV4Signer. */
    private static IHttpMessage toHttpMessage(FullHttpRequest request) {
        return new IHttpMessage() {
            @Override
            public String method() {
                return request.method().name();
            }

            @Override
            public String path() {
                return request.uri();
            }

            @Override
            public String protocol() {
                return request.protocolVersion().text();
            }

            @Override
            public Map<String, List<String>> headers() {
                Map<String, List<String>> result = new LinkedHashMap<>();
                for (var name : request.headers().names()) {
                    result.put(name, request.headers().getAll(name));
                }
                return Collections.unmodifiableMap(result);
            }

            @Override
            public Optional<String> getFirstHeaderValueCaseInsensitive(String key) {
                var value = request.headers().get(key);
                if (value != null) return Optional.of(value);
                // Case-insensitive fallback
                for (var name : request.headers().names()) {
                    if (name.equalsIgnoreCase(key)) {
                        return Optional.ofNullable(request.headers().get(name));
                    }
                }
                return Optional.empty();
            }
        };
    }
}
