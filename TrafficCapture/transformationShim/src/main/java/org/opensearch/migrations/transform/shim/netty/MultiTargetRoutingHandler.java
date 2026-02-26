package org.opensearch.migrations.transform.shim.netty;

import javax.net.ssl.SSLEngine;

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

import org.opensearch.migrations.transform.shim.validation.Target;
import org.opensearch.migrations.transform.shim.validation.TargetResponse;
import org.opensearch.migrations.transform.shim.validation.ValidationResult;
import org.opensearch.migrations.transform.shim.validation.ValidationRule;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
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
    private static final String TARGET_HEADER_PREFIX = "X-Target-";
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
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        boolean keepAlive = Boolean.TRUE.equals(
            ctx.channel().attr(ShimChannelAttributes.KEEP_ALIVE).get());

        var requestMap = HttpMessageUtil.requestToMap(request);
        request.release();

        var futures = dispatchAll(ctx, requestMap);
        handlePrimaryCompletion(ctx, futures, keepAlive);
    }

    private Map<String, CompletableFuture<TargetResponse>> dispatchAll(
        ChannelHandlerContext ctx, Map<String, Object> requestMap
    ) {
        Map<String, CompletableFuture<TargetResponse>> futures = new LinkedHashMap<>();
        for (String name : activeTargets) {
            Target target = targets.get(name);
            Map<String, Object> targetRequestMap = target.requestTransform() != null
                ? deepCopyMap(requestMap) : requestMap;
            futures.put(name, dispatchToTarget(ctx.channel().eventLoop().parent(), target, targetRequestMap, requestMap));
        }
        return futures;
    }

    private void handlePrimaryCompletion(
        ChannelHandlerContext ctx,
        Map<String, CompletableFuture<TargetResponse>> futures,
        boolean keepAlive
    ) {
        futures.get(primaryTarget).whenComplete((primaryResp, primaryEx) ->
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
            })
        );
    }

    private CompletableFuture<TargetResponse> dispatchToTarget(
        io.netty.channel.EventLoopGroup group, Target target, Map<String, Object> requestMap,
        Map<String, Object> originalRequestMap
    ) {
        CompletableFuture<TargetResponse> future = new CompletableFuture<>();
        long startNanos = System.nanoTime();

        try {
            Map<String, Object> targetMap = applyRequestTransform(target, requestMap);
            FullHttpRequest targetRequest = applyAuth(target, HttpMessageUtil.mapToRequest(targetMap));
            URI uri = target.uri();
            targetRequest.headers().set(HttpHeaderNames.HOST,
                uri.getHost() + (uri.getPort() != -1 ? ":" + uri.getPort() : ""));

            connectAndSend(group, target, uri, targetRequest, future, startNanos, originalRequestMap);
        } catch (Exception e) {
            log.error("Error dispatching to target {}", target.name(), e);
            future.complete(TargetResponse.error(target.name(), elapsed(startNanos), e));
        }
        return future;
    }

    private void connectAndSend(
        io.netty.channel.EventLoopGroup group, Target target, URI uri,
        FullHttpRequest reqToSend, CompletableFuture<TargetResponse> future,
        long startNanos, Map<String, Object> originalRequestMap
    ) {
        int port = uri.getPort() != -1 ? uri.getPort() : resolveDefaultPort(uri);
        boolean needsSsl = HTTPS_SCHEME.equalsIgnoreCase(uri.getScheme());

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
                sendRequest(cf.channel(), reqToSend, future, target, startNanos);
            } else {
                releaseIfNeeded(reqToSend);
                future.complete(TargetResponse.error(target.name(), elapsed(startNanos), cf.cause()));
            }
        });
    }

    private static void sendRequest(
        io.netty.channel.Channel channel, FullHttpRequest reqToSend,
        CompletableFuture<TargetResponse> future, Target target, long startNanos
    ) {
        channel.writeAndFlush(reqToSend).addListener((ChannelFutureListener) wf -> {
            if (!wf.isSuccess()) {
                releaseIfNeeded(reqToSend);
                future.complete(TargetResponse.error(target.name(), elapsed(startNanos), wf.cause()));
                wf.channel().close();
            }
        });
    }

    private static void releaseIfNeeded(FullHttpRequest request) {
        if (request.refCnt() > 0) request.release();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> applyRequestTransform(Target target, Map<String, Object> requestMap) {
        if (target.requestTransform() == null) return requestMap;
        Object result = target.requestTransform().transformJson(requestMap);
        if (result instanceof String) {
            return parseJsonMap((String) result, target.name());
        }
        if (result instanceof Map) {
            Map<String, Object> resultMap = (Map<String, Object>) result;
            if (!resultMap.isEmpty()) return resultMap;
        }
        return requestMap;
    }

    private static Map<String, Object> parseJsonMap(String json, String targetName) {
        try {
            return MAPPER.readValue(json, MAP_TYPE_REF);
        } catch (Exception e) {
            throw new TransformException("Request transform failed for target " + targetName, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopyMap(Map<String, Object> original) {
        try {
            byte[] bytes = MAPPER.writeValueAsBytes(original);
            return MAPPER.readValue(bytes, MAP_TYPE_REF);
        } catch (Exception e) {
            throw new TransformException("Failed to deep-copy request map", e);
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
            responses.put(entry.getKey(), collectSingleResponse(entry.getKey(), entry.getValue()));
        }
        return responses;
    }

    private TargetResponse collectSingleResponse(String name, CompletableFuture<TargetResponse> future) {
        try {
            return name.equals(primaryTarget)
                ? future.join()
                : future.orTimeout(secondaryTimeout.toMillis(), TimeUnit.MILLISECONDS).join();
        } catch (Exception e) {
            return TargetResponse.error(name, Duration.ZERO, e);
        }
    }

    private List<ValidationResult> runValidators(Map<String, TargetResponse> allResponses) {
        if (validators.isEmpty() || allResponses.size() < 2) return List.of();

        List<ValidationResult> results = new ArrayList<>();
        for (ValidationRule rule : validators) {
            results.add(runSingleValidator(rule, allResponses));
        }
        return results;
    }

    private static ValidationResult runSingleValidator(
        ValidationRule rule, Map<String, TargetResponse> allResponses
    ) {
        try {
            Map<String, TargetResponse> subset = new LinkedHashMap<>();
            for (String name : rule.targetNames()) {
                TargetResponse resp = allResponses.get(name);
                if (resp != null) subset.put(name, resp);
            }
            return rule.validator().validate(subset);
        } catch (Exception e) {
            return new ValidationResult(rule.name(), false, "ERROR: " + e.getMessage());
        }
    }

    private FullHttpResponse buildFinalResponse(
        TargetResponse primary,
        Map<String, TargetResponse> allResponses,
        List<ValidationResult> validationResults
    ) {
        FullHttpResponse response = buildPrimaryResponse(primary);
        addShimHeaders(response);
        addTargetHeaders(response, allResponses);
        addValidationHeaders(response, validationResults);
        return response;
    }

    private FullHttpResponse buildPrimaryResponse(TargetResponse primary) {
        if (!primary.isSuccess()) {
            return HttpMessageUtil.errorResponse(
                HttpResponseStatus.BAD_GATEWAY,
                "Primary target '" + primary.targetName() + "' failed: " + primary.error().getMessage());
        }
        byte[] body = primary.rawBody() != null ? primary.rawBody() : new byte[0];
        FullHttpResponse response = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.valueOf(primary.statusCode()),
            Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        return response;
    }

    private void addShimHeaders(FullHttpResponse response) {
        response.headers().set("X-Shim-Primary", primaryTarget);
        response.headers().set("X-Shim-Targets", String.join(",", activeTargets));
    }

    private void addTargetHeaders(FullHttpResponse response, Map<String, TargetResponse> allResponses) {
        for (var entry : allResponses.entrySet()) {
            String name = entry.getKey();
            TargetResponse tr = entry.getValue();
            if (tr.isSuccess()) {
                response.headers().set(TARGET_HEADER_PREFIX + name + "-StatusCode", tr.statusCode());
                response.headers().set(TARGET_HEADER_PREFIX + name + "-Latency", tr.latency().toMillis());
            } else {
                String errorMsg = tr.error().getMessage();
                response.headers().set(TARGET_HEADER_PREFIX + name + "-Error",
                    errorMsg != null ? errorMsg : tr.error().getClass().getSimpleName());
            }
        }
    }

    private static void addValidationHeaders(
        FullHttpResponse response, List<ValidationResult> validationResults
    ) {
        if (validationResults.isEmpty()) return;

        boolean allPassed = validationResults.stream().allMatch(ValidationResult::passed);
        boolean anyError = validationResults.stream()
            .anyMatch(r -> r.detail() != null && r.detail().startsWith("ERROR:"));
        String status = computeValidationStatus(allPassed, anyError);
        response.headers().set("X-Validation-Status", status);
        response.headers().set("X-Validation-Details",
            validationResults.stream()
                .map(MultiTargetRoutingHandler::formatValidationResult)
                .collect(Collectors.joining(", ")));
    }

    private static String computeValidationStatus(boolean allPassed, boolean anyError) {
        if (anyError) return "ERROR";
        return allPassed ? "PASS" : "FAIL";
    }

    private static String formatValidationResult(ValidationResult r) {
        String suffix = r.passed() ? "PASS" : "FAIL" + (r.detail() != null ? "[" + r.detail() + "]" : "");
        return r.ruleName() + ":" + suffix;
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
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse backendResponse) {
            try {
                int statusCode = backendResponse.status().code();
                byte[] rawBody = readBody(backendResponse);
                ResponseTransformResult transformed = applyResponseTransform(backendResponse, rawBody, statusCode);
                Map<String, Object> parsedBody = tryParseJson(transformed.body);

                future.complete(new TargetResponse(
                    target.name(), transformed.statusCode, transformed.body, parsedBody,
                    elapsed(startNanos), null));
            } catch (Exception e) {
                log.error("Error processing response from target {}", target.name(), e);
                future.complete(TargetResponse.error(target.name(), elapsed(startNanos), e));
            } finally {
                ctx.close();
            }
        }

        private static byte[] readBody(FullHttpResponse response) {
            byte[] body = new byte[response.content().readableBytes()];
            response.content().readBytes(body);
            return body;
        }

        @SuppressWarnings("unchecked")
        private ResponseTransformResult applyResponseTransform(
            FullHttpResponse backendResponse, byte[] rawBody, int statusCode
        ) {
            if (target.responseTransform() == null) {
                return new ResponseTransformResult(rawBody, statusCode);
            }
            var responseMap = HttpMessageUtil.responseToMap(
                backendResponse.replace(Unpooled.wrappedBuffer(rawBody)));
            var bundled = new LinkedHashMap<String, Object>();
            bundled.put("request", originalRequestMap);
            bundled.put("response", responseMap);
            Object transformResult = target.responseTransform().transformJson(bundled);

            Map<String, Object> transformedMap = parseTransformResult(transformResult);
            if (transformedMap == null) {
                return new ResponseTransformResult(rawBody, statusCode);
            }

            Map<String, Object> responseResult = (Map<String, Object>) transformedMap.get("response");
            if (responseResult == null) responseResult = transformedMap;

            byte[] body = extractBody(responseResult, rawBody);
            int code = extractStatusCode(responseResult, statusCode);
            return new ResponseTransformResult(body, code);
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> parseTransformResult(Object result) {
            try {
                if (result instanceof String) {
                    return MAPPER.readValue((String) result, MAP_TYPE_REF);
                }
                if (result instanceof Map) {
                    return (Map<String, Object>) result;
                }
            } catch (Exception e) {
                log.warn("Failed to parse transform result", e);
            }
            return null;
        }

        private static byte[] extractBody(Map<String, Object> responseResult, byte[] fallback) {
            String bodyStr = HttpMessageUtil.extractBodyString(responseResult);
            return bodyStr != null ? bodyStr.getBytes(StandardCharsets.UTF_8) : fallback;
        }

        private static int extractStatusCode(Map<String, Object> responseResult, int fallback) {
            Object sc = responseResult.get("statusCode");
            if (sc == null) sc = responseResult.get("code");
            return sc instanceof Number ? ((Number) sc).intValue() : fallback;
        }

        private static Map<String, Object> tryParseJson(byte[] body) {
            try {
                return MAPPER.readValue(body, MAP_TYPE_REF);
            } catch (Exception ignored) {
                return null;
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

        private record ResponseTransformResult(byte[] body, int statusCode) {}
    }
}
