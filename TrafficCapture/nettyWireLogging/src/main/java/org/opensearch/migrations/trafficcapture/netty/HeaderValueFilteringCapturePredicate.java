package org.opensearch.migrations.trafficcapture.netty;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.netty.handler.codec.http.HttpRequest;
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
