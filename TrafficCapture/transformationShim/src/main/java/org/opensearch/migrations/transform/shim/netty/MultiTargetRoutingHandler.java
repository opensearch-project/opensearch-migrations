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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};

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

        // Convert to map once, then create independent requests per target
        var requestMap = HttpMessageUtil.requestToMap(request);
        request.release();

        Map<String, CompletableFuture<TargetResponse>> futures = new LinkedHashMap<>();
        for (String name : activeTargets) {
            Target target = targets.get(name);
            // Deep-copy the map for targets with transforms (transform modifies in place)
            Map<String, Object> targetRequestMap = target.requestTransform() != null
                ? deepCopyMap(requestMap) : requestMap;
            futures.put(name, dispatchToTarget(ctx.channel().eventLoop().parent(), target, targetRequestMap, requestMap));
        }

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
                    HttpMessageUtil.writeResponse(ctx, response, keepAlive);
                } catch (Exception e) {
                    log.error("Error building validation response", e);
                    HttpMessageUtil.writeResponse(ctx, HttpMessageUtil.errorResponse(
                        HttpResponseStatus.INTERNAL_SERVER_ERROR, "Validation shim error"), keepAlive);
                }
            });
        });
    }

    private CompletableFuture<TargetResponse> dispatchToTarget(
        io.netty.channel.EventLoopGroup group, Target target, Map<String, Object> requestMap,
        Map<String, Object> originalRequestMap
    ) {
        CompletableFuture<TargetResponse> future = new CompletableFuture<>();
        long startNanos = System.nanoTime();

        try {
            // Apply request transform if configured, then build a fresh Netty request
            Map<String, Object> targetMap = applyRequestTransform(target, requestMap);
            FullHttpRequest targetRequest = HttpMessageUtil.mapToRequest(targetMap);
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
                            target, future, startNanos, originalRequestMap));
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
            log.error("Error dispatching to target {}", target.name(), e);
            future.complete(TargetResponse.error(target.name(), elapsed(startNanos), e));
        }
        return future;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> applyRequestTransform(Target target, Map<String, Object> requestMap) {
        if (target.requestTransform() == null) return requestMap;
        Object result = target.requestTransform().transformJson(requestMap);
        try {
            if (result instanceof String) {
                return MAPPER.readValue((String) result, MAP_TYPE_REF);
            } else if (result instanceof Map) {
                Map<String, Object> resultMap = (Map<String, Object>) result;
                if (!resultMap.isEmpty()) return resultMap;
            }
        } catch (Exception e) {
            log.error("Request transform failed for target {}", target.name(), e);
            throw new RuntimeException("Request transform failed for target " + target.name(), e);
        }
        return requestMap;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopyMap(Map<String, Object> original) {
        try {
            byte[] bytes = MAPPER.writeValueAsBytes(original);
            return MAPPER.readValue(bytes, MAP_TYPE_REF);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deep-copy request map", e);
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
        private final Map<String, Object> originalRequestMap;

        TargetResponseHandler(Target target, CompletableFuture<TargetResponse> future, long startNanos,
                Map<String, Object> originalRequestMap) {
            this.target = target;
            this.future = future;
            this.startNanos = startNanos;
            this.originalRequestMap = originalRequestMap;
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
                    // Bundle {request, response} for the response transform — the new transform
                    // format expects both so it can use request context (URI, params) to drive
                    // response conversion.
                    var responseMap = HttpMessageUtil.responseToMap(backendResponse.replace(
                        Unpooled.wrappedBuffer(rawBody)));
                    var bundled = new LinkedHashMap<String, Object>();
                    bundled.put("request", originalRequestMap);
                    bundled.put("response", responseMap);
                    Object transformResult = target.responseTransform().transformJson(bundled);
                    // Handle JSON string result (from JS JSON.stringify wrapper) or Map
                    Map<String, Object> transformedMap = null;
                    if (transformResult instanceof String) {
                        transformedMap = MAPPER.readValue(
                            (String) transformResult, MAP_TYPE_REF);
                    } else if (transformResult instanceof Map) {
                        transformedMap = (Map<String, Object>) transformResult;
                    }
                    if (transformedMap != null) {
                        // Extract the response from the bundled result
                        Map<String, Object> responseResult = (Map<String, Object>) transformedMap.get("response");
                        if (responseResult == null) responseResult = transformedMap;
                        String bodyStr = HttpMessageUtil.extractBodyString(responseResult);
                        if (bodyStr != null) {
                            rawBody = bodyStr.getBytes(StandardCharsets.UTF_8);
                        }
                        Object sc = responseResult.get("statusCode");
                        if (sc == null) sc = responseResult.get("code");
                        if (sc instanceof Number) statusCode = ((Number) sc).intValue();
                    }
                }

                // Parse body as JSON for validators
                try {
                    parsedBody = MAPPER.readValue(rawBody, MAP_TYPE_REF);
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
