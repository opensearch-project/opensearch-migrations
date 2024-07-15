package org.opensearch.migrations.trafficcapture.netty;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.netty.handler.codec.http.HttpRequest;

public class HeaderValueFilteringCapturePredicate extends RequestCapturePredicate {
    private final Map<String, Pattern> headerToPredicateRegexMap;

    public HeaderValueFilteringCapturePredicate(Map<String, String> suppressCaptureHeaderPairs) {
        super(
            new PassThruHttpHeaders.HttpHeadersToPreserve(suppressCaptureHeaderPairs.keySet().toArray(String[]::new))
        );
        headerToPredicateRegexMap = suppressCaptureHeaderPairs.entrySet()
            .stream()
            .collect(Collectors.toMap(Map.Entry::getKey, kvp -> Pattern.compile(kvp.getValue())));
    }

    @Override
    public CaptureDirective apply(HttpRequest request) {
        return headerToPredicateRegexMap.entrySet()
            .stream()
            .anyMatch(
                kvp -> Optional.ofNullable(request.headers().get(kvp.getKey()))
                    .map(v -> kvp.getValue().matcher(v).matches())
                    .orElse(false)
            ) ? CaptureDirective.DROP : CaptureDirective.CAPTURE;
    }
}
