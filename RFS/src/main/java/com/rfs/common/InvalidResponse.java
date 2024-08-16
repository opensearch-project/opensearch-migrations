package com.rfs.common;

import java.util.ArrayList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.rfs.common.http.HttpResponse;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class InvalidResponse extends RfsException {
    private static final Pattern unknownSetting = Pattern.compile("unknown setting \\[(.+)\\].+");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpResponse response;

    public InvalidResponse(String message, HttpResponse response) {
        super(message);
        this.response = response;
    }

    /**
     * Looks in the invalid error response for any illegal arguments can be removed and the request would succeed.
     * @return The set of illegal arguments to remove from the request. If empty returned, then the request should not be assumed to be safe to repeat.
     */
    public Set<String> getIllegalArguments() {
        try {
            var interimResults = new ArrayList<Map.Entry<String, String>>();
            var bodyNode = objectMapper.readTree(response.body);

            var errorBody = Optional.ofNullable(bodyNode).map(node -> node.get("error"));

            // Check high level cause
            errorBody.map(InvalidResponse::getUnknownSetting).ifPresent(interimResults::add);

            // Check root cause errors
            errorBody.map(node -> node.get("root_cause")).ifPresent(nodes -> {
                nodes.forEach(
                    node -> {
                        Optional.of(node).map(InvalidResponse::getUnknownSetting).ifPresent(interimResults::add);
                    }
                );
            });

            // Check all suppressed errors
            errorBody.map(node -> node.get("suppressed")).ifPresent(nodes -> {
                nodes.forEach(
                    node -> {
                        Optional.of(node).map(InvalidResponse::getUnknownSetting).ifPresent(interimResults::add);
                    }
                );
            });

            var onlyExpectedErrors = interimResults.stream()
                .map(Entry::getKey)
                .allMatch("illegal_argument_exception"::equals);
            if (!onlyExpectedErrors) {
                log.warn("Expecting only invalid argument errors, found additional error types " + interimResults);
                return Set.of();
            }

            return interimResults.stream().map(Entry::getValue).collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("Error parsing error message to attempt recovery" + response.body, e);
            return Set.of();
        }
    }

    private static Map.Entry<String, String> getUnknownSetting(JsonNode json) {
        return Optional.ofNullable(json).map(node -> {
            var typeNode = node.get("type");
            var reasonNode = node.get("reason");
            if (typeNode == null || reasonNode == null) {
                return null;
            }
            return Map.entry(typeNode, reasonNode);
        }).map(entry -> {
            var matcher = unknownSetting.matcher(entry.getValue().asText());
            if (!matcher.matches()) {
                return null;
            }

            return Map.entry(entry.getKey().asText(), matcher.group(1));
        }).orElse(null);
    }
}
