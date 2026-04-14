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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.opensearch.migrations.transform.shim.tracing.RootShimProxyContext;
import org.opensearch.migrations.transform.shim.tracing.ShimRequestContext;
import org.opensearch.migrations.transform.shim.tracing.TargetDispatchContext;
import org.opensearch.migrations.transform.shim.validation.Target;
import org.opensearch.migrations.transform.shim.validation.TargetResponse;
import org.opensearch.migrations.transform.shim.validation.ValidationResult;
import org.opensearch.migrations.transform.shim.validation.ValidationRule;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.pool.AbstractChannelPoolMap;
import io.netty.channel.pool.ChannelPool;
import io.netty.channel.pool.ChannelPoolHandler;
import io.netty.channel.pool.FixedChannelPool;
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
import io.netty.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Netty handler that dispatches a request to N named targets in parallel,
 * collects responses, runs validators, and returns the primary target's response
 * with per-target and validation headers.
 */
@Slf4j
public class MultiTargetRoutingHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final String HTTPS_SCHEME = "https";
    private static final String TARGET_HEADER_PREFIX = "X-Target-";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};
    private static final int MAX_CONNECTIONS_PER_TARGET = 32;
    private static final String HANDLER_READ_TIMEOUT = "readTimeout";
    private static final String HANDLER_RESPONSE = "responseHandler";
    private static final String RESPONSE_KEY = "response";
    private static final String CURSOR_MARK_PARAM = "cursorMark";
    private static final String OPENSEARCH_TARGET = "opensearch";







    /** Structured tuple logger — mirrors replayer's OutputTupleJsonLogger. */
    private static final Logger TUPLE_LOGGER = LoggerFactory.getLogger("OutputTupleJsonLogger");

    private final Map<String, Target> targets;
    private final String primaryTarget;
    private final Set<String> activeTargets;
    private final List<ValidationRule> validators;
    private final Duration secondaryTimeout;
    private final SslContext backendSslContext;
    private final int maxContentLength;
    private final AtomicInteger activeRequests;
    private final AtomicLong requestCounter = new AtomicLong(0);
    private final RootShimProxyContext rootContext;

    /** Connection pools keyed by target name. Lazily initialized on first use. */
    private volatile AbstractChannelPoolMap<String, FixedChannelPool> poolMap;

    public MultiTargetRoutingHandler(
        Map<String, Target> targets,
        String primaryTarget,
        Set<String> activeTargets,
        List<ValidationRule> validators,
        Duration secondaryTimeout,
        SslContext backendSslContext,
        int maxContentLength,
        AtomicInteger activeRequests,
        RootShimProxyContext rootContext
    ) {
        super(false);
        this.targets = targets;
        this.primaryTarget = primaryTarget;
        this.activeTargets = activeTargets;
        this.validators = validators != null ? validators : List.of();
        this.secondaryTimeout = secondaryTimeout;
        this.backendSslContext = backendSslContext;
        this.maxContentLength = maxContentLength;
        this.activeRequests = activeRequests;
        this.rootContext = rootContext;
    }

    /** Backward-compatible constructor without RootShimProxyContext. */
    public MultiTargetRoutingHandler(
        Map<String, Target> targets,
        String primaryTarget,
        Set<String> activeTargets,
        List<ValidationRule> validators,
        Duration secondaryTimeout,
        SslContext backendSslContext,
        int maxContentLength,
        AtomicInteger activeRequests
    ) {
        this(targets, primaryTarget, activeTargets, validators, secondaryTimeout,
            backendSslContext, maxContentLength, activeRequests, null);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        if (poolMap == null) {
            synchronized (this) {
                if (poolMap == null) {
                    poolMap = createPoolMap(ctx.channel().eventLoop().parent());
                }
            }
        }
    }

    private AbstractChannelPoolMap<String, FixedChannelPool> createPoolMap(
        io.netty.channel.EventLoopGroup group
    ) {
        return new AbstractChannelPoolMap<>() {
            @Override
            protected FixedChannelPool newPool(String targetName) {
                Target target = targets.get(targetName);
                URI uri = target.uri();
                int port = uri.getPort() != -1 ? uri.getPort() : resolveDefaultPort(uri);
                boolean needsSsl = HTTPS_SCHEME.equalsIgnoreCase(uri.getScheme());

                Bootstrap bootstrap = new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .remoteAddress(uri.getHost(), port);

                return new FixedChannelPool(bootstrap, new TargetPoolHandler(needsSsl, uri),
                    MAX_CONNECTIONS_PER_TARGET);
            }
        };
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String httpMethod = request.method().name();
        String httpUri = request.uri();

        ShimRequestContext requestCtx = rootContext != null
            ? new ShimRequestContext(rootContext, httpMethod, httpUri)
            : null;

        activeRequests.incrementAndGet();
        boolean keepAlive = Boolean.TRUE.equals(
            ctx.channel().attr(ShimChannelAttributes.KEEP_ALIVE).get());

        var requestMap = HttpMessageUtil.requestToMap(request);
        request.release();

        long requestId = requestCounter.getAndIncrement();
        var dispatchResult = dispatchAll(requestMap, requestCtx);
        handlePrimaryCompletion(ctx, dispatchResult.futures,
            keepAlive, requestMap, requestId, requestCtx);
    }

    private static class DispatchResult {
        final Map<String, CompletableFuture<TargetResponse>> futures;

        DispatchResult(Map<String, CompletableFuture<TargetResponse>> futures) {
            this.futures = futures;
        }
    }

    private DispatchResult dispatchAll(
        Map<String, Object> requestMap,
        ShimRequestContext requestCtx
    ) {
        Map<String, CompletableFuture<TargetResponse>> futures = new LinkedHashMap<>();
        boolean dualMode = activeTargets.size() > 1;
        String uri = (String) requestMap.get("URI");
        // Check for cursorMark in both URL params and JSON request body (P1)
        boolean hasCursorMark = dualMode && (
            (uri != null && uri.contains("cursorMark=")) ||
            requestMap.containsKey(CURSOR_MARK_PARAM)
        );

        for (String name : activeTargets) {
            Target target = targets.get(name);
            Map<String, Object> targetRequestMap = (dualMode || target.requestTransform() != null)
                ? deepCopyMap(requestMap) : requestMap;
            targetRequestMap.put("_targetName", name);
            targetRequestMap.put("_mode", dualMode ? "dual" : "single");

            // In dual mode with cursorMark, split the combined token per target
            if (hasCursorMark) {
                rewriteCursorMarkForTarget(targetRequestMap, name);
            }

            TargetDispatchContext dispatchCtx = requestCtx != null
                ? (TargetDispatchContext) requestCtx.createTargetDispatchContext(name)
                : null;

            // Response transform needs per-target URI (with rewritten cursorMark)
            Map<String, Object> responseRequestMap = hasCursorMark ? targetRequestMap : requestMap;
            futures.put(name, dispatchToTarget(target, targetRequestMap, responseRequestMap, dispatchCtx));
        }
        return new DispatchResult(futures);
    }

    private void handlePrimaryCompletion(
        ChannelHandlerContext ctx,
        Map<String, CompletableFuture<TargetResponse>> futures,
        boolean keepAlive,
        Map<String, Object> requestMap,
        long requestId,
        ShimRequestContext requestCtx
    ) {
        futures.get(primaryTarget).whenComplete((primaryResp, primaryEx) ->
            ctx.channel().eventLoop().execute(() -> {
                try {
                    TargetResponse primary = primaryEx != null
                        ? TargetResponse.error(primaryTarget, Duration.ZERO, primaryEx)
                        : primaryResp;
                    Map<String, TargetResponse> allResponses = collectResponses(futures);

                    List<ValidationResult> results = runValidators(allResponses);
                    FullHttpResponse response = buildFinalResponse(primary, allResponses, results, requestMap);
                    HttpMessageUtil.writeResponse(ctx, response, keepAlive);

                    logTuple(requestId, requestMap, allResponses, results);
                } catch (Exception e) {
                    log.error("Error building validation response", e);
                    if (requestCtx != null) {
                        requestCtx.addTraceException(e, true);
                    }
                    HttpMessageUtil.writeResponse(ctx, HttpMessageUtil.errorResponse(
                        HttpResponseStatus.INTERNAL_SERVER_ERROR, "Validation shim error"), keepAlive);
                } finally {
                    activeRequests.decrementAndGet();
                    if (requestCtx != null) {
                        requestCtx.close();
                    }
                }
            })
        );
    }

    /**
     * Log a structured tuple matching the replayer's ResultsToLogsConsumer.toJSONObject format:
     * {sourceRequest, targetRequest, targetResponses, connectionId, numRequests, numErrors, error}
     */
    private void logTuple(
        long requestId,
        Map<String, Object> sourceRequest,
        Map<String, TargetResponse> allResponses,
        List<ValidationResult> validationResults
    ) {
        if (!TUPLE_LOGGER.isInfoEnabled()) return;
        try {
            var tuple = new LinkedHashMap<String, Object>();
            tuple.put("sourceRequest", buildTupleRequest(sourceRequest));

            var targetResponses = new ArrayList<Map<String, Object>>();
            for (var entry : allResponses.entrySet()) {
                var tr = entry.getValue();
                var respMap = new LinkedHashMap<String, Object>();
                respMap.put("targetName", entry.getKey());
                respMap.put("Status-Code", tr.statusCode());
                respMap.put("response_time_ms", tr.latency().toMillis());
                if (tr.error() != null) {
                    respMap.put("error", tr.error().getMessage());
                }
                targetResponses.add(respMap);
            }
            tuple.put("targetResponses", targetResponses);

            tuple.put("connectionId", String.valueOf(requestId));
            tuple.put("numRequests", 1);
            tuple.put("numErrors", allResponses.values().stream().filter(r -> !r.isSuccess()).count());

            if (!validationResults.isEmpty()) {
                boolean allPassed = validationResults.stream().allMatch(ValidationResult::passed);
                tuple.put("validationStatus", allPassed ? "PASS" : "FAIL");
            }

            TUPLE_LOGGER.info("{}", MAPPER.writeValueAsString(tuple));
        } catch (Exception e) {
            log.debug("Failed to log tuple", e);
        }
    }

    /** Build a tuple-format request map matching replayer's ParsedHttpMessagesAsDicts.convertRequest. */
    private static Map<String, Object> buildTupleRequest(Map<String, Object> requestMap) {
        var tupleReq = new LinkedHashMap<String, Object>();
        tupleReq.put("Request-URI", requestMap.get("URI"));
        tupleReq.put("Method", requestMap.get("method"));
        tupleReq.put("HTTP-Version", requestMap.getOrDefault("protocol", "HTTP/1.1"));
        return tupleReq;
    }

    private CompletableFuture<TargetResponse> dispatchToTarget(
        Target target, Map<String, Object> requestMap, Map<String, Object> originalRequestMap,
        TargetDispatchContext dispatchCtx
    ) {
        CompletableFuture<TargetResponse> future = new CompletableFuture<>();
        long startNanos = System.nanoTime();

        try {
            long reqTransformStart = System.nanoTime();
            Map<String, Object> targetMap = applyRequestTransformOrThrow(target, requestMap, dispatchCtx);
            Duration reqTransformDuration = Duration.ofNanos(System.nanoTime() - reqTransformStart);

            FullHttpRequest targetRequest = applyAuth(target, HttpMessageUtil.mapToRequest(targetMap));
            URI uri = target.uri();
            targetRequest.headers().set(HttpHeaderNames.HOST,
                uri.getHost() + (uri.getPort() != -1 ? ":" + uri.getPort() : ""));

            if (dispatchCtx != null) {
                dispatchCtx.addBytesSent(targetRequest.content().readableBytes());
            }

            sendViaPool(target, targetRequest, future, startNanos, originalRequestMap,
                reqTransformDuration, dispatchCtx);
        } catch (Exception e) {
            log.error("Error dispatching to target {}", target.name(), e);
            if (dispatchCtx != null) {
                dispatchCtx.addTraceException(e, true);
                dispatchCtx.close();
            }
            future.complete(TargetResponse.error(target.name(), elapsed(startNanos), e));
        }
        return future;
    }

    private void sendViaPool(
        Target target, FullHttpRequest reqToSend, CompletableFuture<TargetResponse> future,
        long startNanos, Map<String, Object> originalRequestMap, Duration reqTransformDuration,
        TargetDispatchContext dispatchCtx
    ) {
        ChannelPool pool = poolMap.get(target.name());
        Future<Channel> acquireFuture = pool.acquire();
        acquireFuture.addListener((Future<Channel> f) -> {
            if (!f.isSuccess()) {
                releaseIfNeeded(reqToSend);
                future.complete(TargetResponse.error(target.name(), elapsed(startNanos), f.cause()));
                return;
            }
            Channel ch = f.getNow();
            ch.pipeline().addLast(HANDLER_READ_TIMEOUT, new ReadTimeoutHandler(
                secondaryTimeout.toSeconds(), TimeUnit.SECONDS));
            ch.pipeline().addLast(HANDLER_RESPONSE, new PooledTargetResponseHandler(
                target, future, startNanos, originalRequestMap, reqTransformDuration, pool, ch,
                dispatchCtx));

            ch.writeAndFlush(reqToSend).addListener((ChannelFutureListener) wf -> {
                if (!wf.isSuccess()) {
                    releaseIfNeeded(reqToSend);
                    future.complete(TargetResponse.error(target.name(), elapsed(startNanos), wf.cause()));
                    pool.release(ch);
                }
            });
        });
    }

    private static void releaseIfNeeded(FullHttpRequest request) {
        if (request.refCnt() > 0) request.release();
    }

    private Map<String, Object> applyRequestTransform(
        Target target, Map<String, Object> requestMap, TargetDispatchContext dispatchCtx
    ) {
        if (target.requestTransform() == null) return requestMap;
        if (dispatchCtx != null) {
            try (var transformCtx = dispatchCtx.createTransformContext("request")) {
                try {
                    return doRequestTransform(target, requestMap);
                } catch (Exception e) {
                    transformCtx.addTraceException(e, true);
                    throw e;
                }
            }
        }
        return doRequestTransform(target, requestMap);
    }

    /**
     * Apply the request transform, wrapping any failure in TransformException
     * so callers can distinguish transform errors from upstream failures.
     */
    private Map<String, Object> applyRequestTransformOrThrow(
        Target target, Map<String, Object> requestMap, TargetDispatchContext dispatchCtx
    ) {
        try {
            return applyRequestTransform(target, requestMap, dispatchCtx);
        } catch (Exception e) {
            throw new TransformException(
                String.format("Request transform failed for target '%s'", target.name()), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> doRequestTransform(Target target, Map<String, Object> requestMap) {
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
        var copy = new LinkedHashMap<String, Object>();
        for (var entry : original.entrySet()) {
            copy.put(entry.getKey(), deepCopyValue(entry.getValue()));
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopyValue(Object value) {
        if (value instanceof Map) {
            return deepCopyMap((Map<String, Object>) value);
        } else if (value instanceof List) {
            return ((List<?>) value).stream()
                .map(MultiTargetRoutingHandler::deepCopyValue)
                .collect(Collectors.toCollection(ArrayList::new));
        }
        return value;
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
        List<ValidationResult> validationResults,
        Map<String, Object> requestMap
    ) {
        FullHttpResponse response = buildPrimaryResponse(primary);

        if (allResponses.size() > 1) {
            String sentCursorMark = extractQueryParam(
                (String) requestMap.get("URI"), CURSOR_MARK_PARAM);
            response = mergeCursorTokens(response, allResponses, sentCursorMark);
        }

        addShimHeaders(response);
        addTargetHeaders(response, allResponses);
        addValidationHeaders(response, validationResults);
        return response;
    }

    private FullHttpResponse buildPrimaryResponse(TargetResponse primary) {
        if (!primary.isSuccess()) {
            final Throwable err = primary.error();
            // Transform failures (wrapped in TransformException) return 500
            // upstream communication failures return 502
            final HttpResponseStatus status;
            if (err instanceof TransformException) {
                status = HttpResponseStatus.INTERNAL_SERVER_ERROR;
            } else {
                status = HttpResponseStatus.BAD_GATEWAY;
            }
            return HttpMessageUtil.errorResponse(
                status,
                String.format("Primary target '%s' failed: %s", primary.targetName(), err.getMessage()));
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

    // --- Cursor pagination dual-mode support ---

    /**
     * In dual mode, rewrite the cursorMark query param for each target.
     * Combined token format: base64({"solr":"<native>","os":"<os_token>"})
     * For initial request (cursorMark=*), no rewrite needed.
     * For Solr: extract the "solr" portion (native Solr cursorMark).
     * For OpenSearch: extract the "os" portion (base64-encoded sort values).
     */
    static void rewriteCursorMarkForTarget(Map<String, Object> requestMap, String targetName) {
        String uri = (String) requestMap.get("URI");
        if (uri == null) return;

        String cursorMark = extractQueryParam(uri, CURSOR_MARK_PARAM);
        if (cursorMark == null || "*".equals(cursorMark)) return;

        try {
            String decoded = new String(java.util.Base64.getUrlDecoder().decode(cursorMark), StandardCharsets.UTF_8);
            Map<String, Object> combined = MAPPER.readValue(decoded, MAP_TYPE_REF);

            String targetToken;
            if (OPENSEARCH_TARGET.equals(targetName) && combined.containsKey("os")) {
                targetToken = (String) combined.get("os");
            } else if (combined.containsKey("solr")) {
                targetToken = (String) combined.get("solr");
            } else {
                return; // Not a combined token, pass through as-is
            }

            String newUri = replaceQueryParam(uri, CURSOR_MARK_PARAM, targetToken);
            requestMap.put("URI", newUri);
        } catch (Exception e) {
            // Not a combined token — unexpected in dual mode, log for debugging
            log.warn("cursorMark is not a combined token, passing through: {}", e.getMessage());
        }
    }

    /**
     * After both targets respond, merge their nextCursorMark tokens into a combined token.
     * Rewrites the primary response body with the combined nextCursorMark.
     *
     * Contract: each target's cursor token is treated as an opaque string. The TypeScript
     * transform layer produces base64-encoded sort values for OpenSearch and Solr returns
     * its native cursor token. This method combines them without interpreting the format.
     *
     * TODO: Consider extracting cursor merge/split into a CursorStrategy interface if
     * additional pagination strategies are needed beyond the current solr+os dual mode.
     */
    @SuppressWarnings("unchecked")
    FullHttpResponse mergeCursorTokens(
        FullHttpResponse response, Map<String, TargetResponse> allResponses, String sentCursorMark
    ) {
        String solrToken = extractCursorToken(allResponses, "solr");
        String osToken = extractCursorToken(allResponses, OPENSEARCH_TARGET);

        if (solrToken == null || osToken == null) {
            logPartialCursorWarning(solrToken, osToken);
            return response;
        }

        if (isPaginationEnded(sentCursorMark, solrToken, osToken)) {
            log.debug("Both targets returned same cursor as sent — end of results");
            return response;
        }

        try {
            String combinedToken = buildCombinedToken(solrToken, osToken);
            return rewriteResponseWithToken(response, combinedToken);
        } catch (Exception e) {
            log.warn("Failed to merge cursor tokens: {}", e.getMessage());
            return response;
        }
    }

    private void logPartialCursorWarning(String solrToken, String osToken) {
        if (solrToken != null || osToken != null) {
            log.warn("Only one target returned a cursor token (solr={}, os={}), skipping merge",
                solrToken != null ? "present" : "missing", osToken != null ? "present" : "missing");
        }
    }

    private boolean isPaginationEnded(String sentCursorMark, String solrToken, String osToken) {
        if (sentCursorMark == null || "*".equals(sentCursorMark)) {
            return false;
        }
        try {
            String decoded = new String(
                java.util.Base64.getUrlDecoder().decode(sentCursorMark), StandardCharsets.UTF_8);
            Map<String, Object> sentCombined = MAPPER.readValue(decoded, MAP_TYPE_REF);
            String sentSolr = (String) sentCombined.get("solr");
            String sentOs = (String) sentCombined.get("os");
            return solrToken.equals(sentSolr) && osToken.equals(sentOs);
        } catch (Exception e) {
            log.warn("Sent cursorMark is not a combined token, proceeding with merge: {}",
                e.getMessage());
            return false;
        }
    }

    private String extractCursorToken(Map<String, TargetResponse> allResponses, String targetKey) {
        TargetResponse tr = allResponses.get(targetKey);
        if (tr != null && tr.isSuccess() && tr.parsedBody() != null) {
            Object nextCursor = tr.parsedBody().get("nextCursorMark");
            if (nextCursor != null) return nextCursor.toString();
        }
        return null;
    }

    private String buildCombinedToken(String solrToken, String osToken) throws Exception {
        Map<String, Object> combined = new LinkedHashMap<>();
        combined.put("v", 1);
        combined.put("solr", solrToken);
        combined.put("os", osToken);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(MAPPER.writeValueAsBytes(combined));
    }

    private FullHttpResponse rewriteResponseWithToken(
        FullHttpResponse response, String combinedToken
    ) throws Exception {
        byte[] bodyBytes = new byte[response.content().readableBytes()];
        response.content().getBytes(response.content().readerIndex(), bodyBytes);
        Map<String, Object> body = MAPPER.readValue(bodyBytes, MAP_TYPE_REF);
        body.put("nextCursorMark", combinedToken);
        byte[] newBody = MAPPER.writeValueAsBytes(body);

        HttpResponseStatus status = response.status();
        response.release();
        FullHttpResponse newResponse = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(newBody));
        newResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, newBody.length);
        newResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        return newResponse;
    }

    static String extractQueryParam(String uri, String param) {
        int qIdx = uri.indexOf('?');
        if (qIdx < 0) return null;
        String query = uri.substring(qIdx + 1);
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && param.equals(kv[0])) {
                return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    static String replaceQueryParam(String uri, String param, String newValue) {
        String encoded = java.net.URLEncoder.encode(newValue, StandardCharsets.UTF_8);
        return uri.replaceFirst(
            "([?&])" + param + "=[^&]*",
            "$1" + param + "=" + encoded);
    }

    // --- End cursor pagination support ---

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
                // TODO: enable if latency headers matter
                // response.headers().set(TARGET_HEADER_PREFIX + name + "-Latency", tr.latency().toMillis());
                // response.headers().set(TARGET_HEADER_PREFIX + name + "-ClusterLatency", tr.clusterLatency().toMillis());
                // response.headers().set(TARGET_HEADER_PREFIX + name + "-RequestTransformLatency", tr.requestTransformLatency().toMillis());
                // response.headers().set(TARGET_HEADER_PREFIX + name + "-ResponseTransformLatency", tr.responseTransformLatency().toMillis());
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
        if (r.passed()) {
            return r.ruleName() + ":PASS";
        }
        String detail = r.detail() != null ? "[" + r.detail() + "]" : "";
        return r.ruleName() + ":FAIL" + detail;
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

    /** Channel pool handler that sets up the HTTP client pipeline for pooled connections. */
    private class TargetPoolHandler implements ChannelPoolHandler {
        private final boolean needsSsl;
        private final URI uri;

        TargetPoolHandler(boolean needsSsl, URI uri) {
            this.needsSsl = needsSsl;
            this.uri = uri;
        }

        @Override
        public void channelCreated(Channel ch) {
            var p = ch.pipeline();
            if (needsSsl && backendSslContext != null) {
                SSLEngine sslEngine = backendSslContext.newEngine(ch.alloc());
                sslEngine.setUseClientMode(true);
                p.addLast("ssl", new SslHandler(sslEngine));
            }
            p.addLast("httpCodec", new HttpClientCodec());
            p.addLast("httpAggregator", new HttpObjectAggregator(maxContentLength));
        }

        @Override
        public void channelReleased(Channel ch) {
            // Remove per-request handlers so the channel is clean for reuse
            if (ch.pipeline().get(HANDLER_READ_TIMEOUT) != null) ch.pipeline().remove(HANDLER_READ_TIMEOUT);
            if (ch.pipeline().get(HANDLER_RESPONSE) != null) ch.pipeline().remove(HANDLER_RESPONSE);
        }

        @Override
        public void channelAcquired(Channel ch) {
            // No-op
        }
    }

    /**
     * Receives the backend response for a single target using a pooled connection.
     * Releases the channel back to the pool after processing.
     */
    @Slf4j
    static class PooledTargetResponseHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
        private final Target target;
        private final CompletableFuture<TargetResponse> future;
        private final long startNanos;
        private final Map<String, Object> originalRequestMap;
        private final Duration reqTransformDuration;
        private final ChannelPool pool;
        private final Channel channel;
        private final TargetDispatchContext dispatchCtx;

        PooledTargetResponseHandler(Target target, CompletableFuture<TargetResponse> future, long startNanos,
                Map<String, Object> originalRequestMap, Duration reqTransformDuration,
                ChannelPool pool, Channel channel, TargetDispatchContext dispatchCtx) {
            this.target = target;
            this.future = future;
            this.startNanos = startNanos;
            this.originalRequestMap = originalRequestMap;
            this.reqTransformDuration = reqTransformDuration;
            this.pool = pool;
            this.channel = channel;
            this.dispatchCtx = dispatchCtx;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse backendResponse) {
            try {
                int statusCode = backendResponse.status().code();
                byte[] rawBody = readBody(backendResponse);
                byte[] finalBody = rawBody;
                int finalStatusCode = statusCode;
                Duration respTransformDuration = Duration.ZERO;

                if (dispatchCtx != null) {
                    dispatchCtx.addBytesReceived(rawBody.length);
                }

                if (target.responseTransform() != null) {
                    long respTransformStart = System.nanoTime();
                    Object[] transformed = applyResponseTransformWithInstrumentation(
                        backendResponse, rawBody, statusCode, dispatchCtx);
                    respTransformDuration = Duration.ofNanos(System.nanoTime() - respTransformStart);
                    finalBody = (byte[]) transformed[0];
                    finalStatusCode = (int) transformed[1];
                }

                if (dispatchCtx != null) {
                    dispatchCtx.setStatusCode(finalStatusCode);
                    dispatchCtx.close();
                }

                Map<String, Object> parsedBody = tryParseJson(finalBody);
                future.complete(new TargetResponse(
                    target.name(), finalStatusCode, finalBody, parsedBody,
                    elapsed(startNanos), reqTransformDuration, respTransformDuration, null));
            } catch (Exception e) {
                log.error("Error processing response from target {}", target.name(), e);
                if (dispatchCtx != null) {
                    dispatchCtx.addTraceException(e, true);
                    dispatchCtx.close();
                }
                future.complete(TargetResponse.error(target.name(), elapsed(startNanos), e));
            } finally {
                pool.release(channel);
            }
        }

        private static byte[] readBody(FullHttpResponse response) {
            byte[] body = new byte[response.content().readableBytes()];
            response.content().readBytes(body);
            return body;
        }

        private Object[] applyResponseTransformWithInstrumentation(
            FullHttpResponse backendResponse, byte[] rawBody, int statusCode,
            TargetDispatchContext dispatchCtx
        ) {
            if (dispatchCtx != null) {
                try (var transformCtx = dispatchCtx.createTransformContext(RESPONSE_KEY)) {
                    try {
                        return applyResponseTransform(backendResponse, rawBody, statusCode);
                    } catch (Exception e) {
                        transformCtx.addTraceException(e, true);
                        throw e;
                    }
                }
            }
            return applyResponseTransform(backendResponse, rawBody, statusCode);
        }

        @SuppressWarnings("unchecked")
        private Object[] applyResponseTransform(
            FullHttpResponse backendResponse, byte[] rawBody, int statusCode
        ) {
            var responseMap = HttpMessageUtil.responseToMap(
                backendResponse.replace(Unpooled.wrappedBuffer(rawBody)));
            var bundled = new LinkedHashMap<String, Object>();
            bundled.put("request", originalRequestMap);
            bundled.put(RESPONSE_KEY, responseMap);
            Object transformResult = target.responseTransform().transformJson(bundled);

            Map<String, Object> transformedMap = parseTransformResult(transformResult);
            if (transformedMap == null) {
                return new Object[]{rawBody, statusCode};
            }

            Map<String, Object> responseResult = (Map<String, Object>) transformedMap.get(RESPONSE_KEY);
            if (responseResult == null) responseResult = transformedMap;

            byte[] body = extractBody(responseResult, rawBody);
            int code = extractStatusCode(responseResult, statusCode);
            return new Object[]{body, code};
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
            if (dispatchCtx != null) {
                dispatchCtx.addTraceException(cause, true);
                dispatchCtx.close();
            }
            future.complete(TargetResponse.error(target.name(), elapsed(startNanos), cause));
            pool.release(channel);
        }

        private static Duration elapsed(long startNanos) {
            return Duration.ofNanos(System.nanoTime() - startNanos);
        }
    }
}
