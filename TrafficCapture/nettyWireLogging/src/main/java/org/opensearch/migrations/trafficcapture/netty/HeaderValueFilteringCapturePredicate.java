package org.opensearch.migrations.trafficcapture.netty;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.util.AsciiString;
import lombok.Builder;

public class HeaderValueFilteringCapturePredicate extends RequestCapturePredicate {
    private final Pattern method;
    private final Pattern path;
    private final Pattern protocol;
    private final Pattern methodAndPathPattern;
    private final Map<String, Pattern> headerToPredicateRegexMap;

    @Builder
    public HeaderValueFilteringCapturePredicate(String methodPattern,
                                                String pathPattern,
                                                String protocolPattern,
                                                String methodAndPathPattern,
                                                Map<String, String> suppressCaptureHeaderPairs) {
        super(new PassThruHttpHeaders.HttpHeadersToPreserve(
            Optional.ofNullable(suppressCaptureHeaderPairs)
                .map(m->m.keySet().toArray(String[]::new))
                .orElse(null)
            ));
        this.method               = methodPattern == null   ? null : Pattern.compile(methodPattern);
        this.path                 = pathPattern == null     ? null : Pattern.compile(pathPattern);
        this.protocol             = protocolPattern == null ? null : Pattern.compile(protocolPattern);
        this.methodAndPathPattern = methodAndPathPattern == null ? null : Pattern.compile(methodAndPathPattern);
        headerToPredicateRegexMap = suppressCaptureHeaderPairs == null ? null :
            suppressCaptureHeaderPairs.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, kvp -> Pattern.compile(kvp.getValue())));
    }

    @Override
    public CaptureDirective apply(HttpRequest request) {
        return
            patternMatches(method,           () -> request.method().name()) ||
            patternMatches(path,             request::uri) ||
            patternMatches(protocol,         () -> request.protocolVersion().text()) ||
            patternMatches(methodAndPathPattern, () -> request.method().name()+" "+request.uri()) ||
            headersMatch(request) ?
                CaptureDirective.DROP : CaptureDirective.CAPTURE;
    }

    /**
     * H2 evaluation: pseudo-headers ({@code :method}, {@code :path}, {@code :scheme},
     * {@code :authority}) per RFC 7540 §8.1.2.3 are mapped onto the existing H1 filters,
     * and regular H2 headers are evaluated against the same {@code suppressCaptureHeaderPairs}
     * map.
     *
     * <p>The {@code protocolPattern} doesn't apply to H2 — there is no "HTTP/2.x" protocol
     * line in H2 — so a configured pattern matching {@code HTTP/2.*} (the legacy guard) is
     * a no-op here. That's intentional: when {@code --enableHttp2} is set the proxy strips
     * the legacy pattern; when it isn't set the H2 pipeline isn't reached.
     */
    @Override
    public CaptureDirective forH2Stream(int streamId, Http2Headers headers) {
        if (headers == null) {
            return CaptureDirective.CAPTURE;
        }
        var methodValue = headers.method();
        var pathValue = headers.path();
        if (patternMatches(method, () -> methodValue == null ? "" : methodValue.toString())) {
            return CaptureDirective.DROP;
        }
        if (patternMatches(path, () -> pathValue == null ? "" : pathValue.toString())) {
            return CaptureDirective.DROP;
        }
        if (patternMatches(methodAndPathPattern,
                () -> (methodValue == null ? "" : methodValue.toString())
                    + " " + (pathValue == null ? "" : pathValue.toString()))) {
            return CaptureDirective.DROP;
        }
        if (headerToPredicateRegexMap != null) {
            for (var kvp : headerToPredicateRegexMap.entrySet()) {
                var headerValue = headers.get(AsciiString.cached(kvp.getKey().toLowerCase(java.util.Locale.ROOT)));
                if (headerValue != null && kvp.getValue().matcher(headerValue.toString()).matches()) {
                    return CaptureDirective.DROP;
                }
            }
        }
        return CaptureDirective.CAPTURE;
    }

    private static boolean patternMatches(Pattern pattern, Supplier<String> stringGetter) {
        return Optional.ofNullable(pattern).map(p->p.matcher(stringGetter.get()).matches()).orElse(false);
    }

    private boolean headersMatch(HttpRequest request) {
        return Optional.ofNullable(headerToPredicateRegexMap).map(m->m.entrySet()
                .stream()
                .anyMatch(
                    kvp -> Optional.ofNullable(request.headers().get(kvp.getKey()))
                        .map(v -> kvp.getValue().matcher(v).matches())
                        .orElse(false)
                ))
            .orElse(false);
    }
}
