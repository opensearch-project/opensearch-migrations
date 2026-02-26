package org.opensearch.migrations.transform.shim.netty;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.net.ssl.SSLEngine;

import org.opensearch.migrations.transform.shim.validation.ResponseValidator;
import org.opensearch.migrations.transform.shim.validation.Target;
import org.opensearch.migrations.transform.shim.validation.TargetResponse;
import org.opensearch.migrations.transform.shim.validation.ValidationResult;
import org.opensearch.migrations.transform.shim.validation.ValidationRule;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import lombok.extern.slf4j.Slf4j;

/**
 * Netty handler that dispatches a request to N named targets in parallel,
 * collects responses, runs validators, and returns the primary target's response
 * with per-target and validation headers.
 */
@Slf4j
public class MultiTargetRoutingHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final int MAX_CONTENT_LENGTH = 10 * 1024 * 1024;
    private static final String HTTPS_SCHEME = "https";

    private final Map<String, Target> targets;
    private final String primaryTarget;
    private final Set<String> activeTargets;
    private final List<ValidationRule> validators;
    private final Duration secondaryTimeout;
    private final SslContext backendSslContext;

    public MultiTargetRoutingHandler(
        Map<String, Target> targets,
        String primaryTarget,
        Set<String> activeTargets,
        List<ValidationRule> validators,
        Duration secondaryTimeout,
        SslContext backendSslContext
    ) {
        super(false);
        this.targets = targets;
        this.primaryTarget = primaryTarget;
        this.activeTargets = activeTargets;
        this.validators = validators != null ? validators : List.of();
        this.secondaryTimeout = secondaryTimeout;
        this.backendSslContext = backendSslContext;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        boolean keepAlive = Boolean.TRUE.equals(
            ctx.channel().attr(ShimChannelAttributes.KEEP_ALIVE).get());

        Map<String, CompletableFuture<TargetResponse>> futures = new LinkedHashMap<>();
        for (String name : activeTargets) {
            Target target = targets.get(name);
            futures.put(name, dispatchToTarget(ctx.channel().eventLoop().parent(), target, request));
        }
        request.release();

        // When primary completes, collect secondaries (with timeout), validate, respond
        futures.get(primaryTarget).whenComplete((primaryResp, primaryEx) -> {
            ctx.channel().eventLoop().execute(() -> {
                try {
                    TargetResponse primary = primaryEx != null
                        ? TargetResponse.error(primaryTarget, Duration.ZERO, primaryEx)
                        : primaryResp;

                    Map<String, TargetResponse> allResponses = collectResponses(futures);
                    List<ValidationResult> results = runValidators(allResponses);
                    FullHttpResponse response = buildFinalResponse(primary, allResponses, results);
                    writeResponse(ctx, response, keepAlive);
                } catch (Exception e) {
                    log.error("Error building validation response", e);
                    writeResponse(ctx, HttpMessageUtil.errorResponse(
                        HttpResponseStatus.INTERNAL_SERVER_ERROR, "Validation shim error"), keepAlive);
                }
            });
        });
    }

    private CompletableFuture<TargetResponse> dispatchToTarget(
        io.netty.channel.EventLoopGroup group, Target target, FullHttpRequest originalRequest
    ) {
        CompletableFuture<TargetResponse> future = new CompletableFuture<>();
        long startNanos = System.nanoTime();

        try {
            // Copy request for this target (independent copy, no shared refcount)
            FullHttpRequest targetRequest = originalRequest.copy();
            targetRequest = applyRequestTransform(target, targetRequest);
            targetRequest = applyAuth(target, targetRequest);

            // Set Host header for this target
            URI uri = target.uri();
            targetRequest.headers().set(HttpHeaderNames.HOST,
                uri.getHost() + (uri.getPort() != -1 ? ":" + uri.getPort() : ""));

            int port = uri.getPort() != -1 ? uri.getPort() : resolveDefaultPort(uri);
            boolean needsSsl = HTTPS_SCHEME.equalsIgnoreCase(uri.getScheme());

            FullHttpRequest reqToSend = targetRequest;
            Bootstrap b = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        var p = ch.pipeline();
                        if (needsSsl && backendSslContext != null) {
                            SSLEngine sslEngine = backendSslContext.newEngine(ch.alloc());
                            sslEngine.setUseClientMode(true);
                            p.addLast("ssl", new SslHandler(sslEngine));
                        }
                        p.addLast("httpCodec", new HttpClientCodec());
                        p.addLast("httpAggregator", new HttpObjectAggregator(MAX_CONTENT_LENGTH));
                        p.addLast("readTimeout", new ReadTimeoutHandler(
                            secondaryTimeout.toSeconds(), TimeUnit.SECONDS));
                        p.addLast("responseHandler", new TargetResponseHandler(
                            target, future, startNanos));
                    }
                });

            b.connect(uri.getHost(), port).addListener((ChannelFutureListener) cf -> {
                if (cf.isSuccess()) {
                    cf.channel().writeAndFlush(reqToSend).addListener((ChannelFutureListener) wf -> {
                        if (!wf.isSuccess()) {
                            if (reqToSend.refCnt() > 0) reqToSend.release();
                            future.complete(TargetResponse.error(target.name(),
                                elapsed(startNanos), wf.cause()));
                            wf.channel().close();
                        }
                    });
                } else {
                    if (reqToSend.refCnt() > 0) reqToSend.release();
                    future.complete(TargetResponse.error(target.name(),
                        elapsed(startNanos), cf.cause()));
                }
            });
        } catch (Exception e) {
            future.complete(TargetResponse.error(target.name(), elapsed(startNanos), e));
        }
        return future;
    }

    @SuppressWarnings("unchecked")
    private FullHttpRequest applyRequestTransform(Target target, FullHttpRequest request) {
        if (target.requestTransform() == null) return request;
        try {
            var requestMap = HttpMessageUtil.requestToMap(request);
            var transformedMap = (Map<String, Object>) target.requestTransform().transformJson(requestMap);
            request.release();
            return HttpMessageUtil.mapToRequest(transformedMap);
        } catch (Exception e) {
            request.release();
            throw new RuntimeException("Request transform failed for target " + target.name(), e);
        }
    }

    private FullHttpRequest applyAuth(Target target, FullHttpRequest request) {
        if (target.authHandlerSupplier() == null) return request;
        var embedded = new EmbeddedChannel(target.authHandlerSupplier().get());
        embedded.writeInbound(request);
        FullHttpRequest authed = embedded.readInbound();
        embedded.close();
        return authed;
    }

    private Map<String, TargetResponse> collectResponses(
        Map<String, CompletableFuture<TargetResponse>> futures
    ) {
        Map<String, TargetResponse> responses = new LinkedHashMap<>();
        for (var entry : futures.entrySet()) {
            try {
                boolean isPrimary = entry.getKey().equals(primaryTarget);
                TargetResponse resp = isPrimary
                    ? entry.getValue().join()
                    : entry.getValue().orTimeout(
                        secondaryTimeout.toMillis(), TimeUnit.MILLISECONDS).join();
                responses.put(entry.getKey(), resp);
            } catch (Exception e) {
                responses.put(entry.getKey(),
                    TargetResponse.error(entry.getKey(), Duration.ZERO, e));
            }
        }
        return responses;
    }

    private List<ValidationResult> runValidators(Map<String, TargetResponse> allResponses) {
        if (validators.isEmpty() || allResponses.size() < 2) return List.of();

        List<ValidationResult> results = new ArrayList<>();
        for (ValidationRule rule : validators) {
            try {
                // Filter responses to only the targets this rule cares about
                Map<String, TargetResponse> subset = new LinkedHashMap<>();
                for (String name : rule.targetNames()) {
                    TargetResponse resp = allResponses.get(name);
                    if (resp != null) subset.put(name, resp);
                }
                results.add(rule.validator().validate(subset));
            } catch (Exception e) {
                results.add(new ValidationResult(rule.name(), false, "ERROR: " + e.getMessage()));
            }
        }
        return results;
    }

    private FullHttpResponse buildFinalResponse(
        TargetResponse primary,
        Map<String, TargetResponse> allResponses,
        List<ValidationResult> validationResults
    ) {
        FullHttpResponse response;
        if (!primary.isSuccess()) {
            response = HttpMessageUtil.errorResponse(
                HttpResponseStatus.BAD_GATEWAY,
                "Primary target '" + primary.targetName() + "' failed: " + primary.error().getMessage());
        } else {
            byte[] body = primary.rawBody() != null ? primary.rawBody() : new byte[0];
            response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(primary.statusCode()),
                Unpooled.wrappedBuffer(body));
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        }

        // Shim metadata headers
        response.headers().set("X-Shim-Primary", primaryTarget);
        response.headers().set("X-Shim-Targets",
            String.join(",", activeTargets));

        // Per-target headers
        for (var entry : allResponses.entrySet()) {
            String name = entry.getKey();
            TargetResponse tr = entry.getValue();
            if (tr.isSuccess()) {
                response.headers().set("X-Target-" + name + "-StatusCode", tr.statusCode());
                response.headers().set("X-Target-" + name + "-Latency", tr.latency().toMillis());
            } else {
                response.headers().set("X-Target-" + name + "-Error",
                    tr.error().getMessage() != null ? tr.error().getMessage() : tr.error().getClass().getSimpleName());
            }
        }

        // Validation headers
        if (!validationResults.isEmpty()) {
            boolean allPassed = validationResults.stream().allMatch(ValidationResult::passed);
            boolean anyError = validationResults.stream()
                .anyMatch(r -> r.detail() != null && r.detail().startsWith("ERROR:"));
            String status = anyError ? "ERROR" : (allPassed ? "PASS" : "FAIL");
            response.headers().set("X-Validation-Status", status);
            response.headers().set("X-Validation-Details",
                validationResults.stream()
                    .map(r -> r.ruleName() + ":" + (r.passed() ? "PASS" : "FAIL"
                        + (r.detail() != null ? "[" + r.detail() + "]" : "")))
                    .collect(Collectors.joining(", ")));
        }

        return response;
    }

    private static void writeResponse(ChannelHandlerContext ctx, FullHttpResponse response, boolean keepAlive) {
        if (keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, "keep-alive");
            ctx.writeAndFlush(response);
        } else {
            response.headers().set(HttpHeaderNames.CONNECTION, "close");
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private static int resolveDefaultPort(URI uri) {
        return HTTPS_SCHEME.equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static Duration elapsed(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Unhandled exception in multi-target routing handler", cause);
        ctx.close();
    }

    /**
     * Receives the backend response for a single target, optionally applies
     * the response transform, and completes the future.
     */
    @Slf4j
    static class TargetResponseHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
        private final Target target;
        private final CompletableFuture<TargetResponse> future;
        private final long startNanos;

        TargetResponseHandler(Target target, CompletableFuture<TargetResponse> future, long startNanos) {
            this.target = target;
            this.future = future;
            this.startNanos = startNanos;
        }

        @Override
        @SuppressWarnings("unchecked")
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse backendResponse) {
            try {
                int statusCode = backendResponse.status().code();
                byte[] rawBody = new byte[backendResponse.content().readableBytes()];
                backendResponse.content().readBytes(rawBody);

                Map<String, Object> parsedBody = null;
                if (target.responseTransform() != null) {
                    // Re-create a response for the transform
                    var responseMap = HttpMessageUtil.responseToMap(backendResponse.replace(
                        Unpooled.wrappedBuffer(rawBody)));
                    var transformedMap = (Map<String, Object>) target.responseTransform()
                        .transformJson(responseMap);
                    // Extract transformed body
                    String bodyStr = HttpMessageUtil.extractBodyString(transformedMap);
                    if (bodyStr != null) {
                        rawBody = bodyStr.getBytes(StandardCharsets.UTF_8);
                    }
                    Object sc = transformedMap.get("statusCode");
                    if (sc instanceof Number) statusCode = ((Number) sc).intValue();
                }

                // Parse body as JSON for validators
                try {
                    parsedBody = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(rawBody, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                } catch (Exception ignored) {
                    // Not JSON — leave parsedBody null
                }

                future.complete(new TargetResponse(
                    target.name(), statusCode, rawBody, parsedBody, elapsed(startNanos), null));
            } catch (Exception e) {
                log.error("Error processing response from target {}", target.name(), e);
                future.complete(TargetResponse.error(target.name(), elapsed(startNanos), e));
            } finally {
                ctx.close();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("Backend response error for target {}", target.name(), cause);
            future.complete(TargetResponse.error(target.name(), elapsed(startNanos), cause));
            ctx.close();
        }

        private static Duration elapsed(long startNanos) {
            return Duration.ofNanos(System.nanoTime() - startNanos);
        }
    }
}
