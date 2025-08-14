package org.opensearch.migrations.bulkload.common;

import java.util.ArrayList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.opensearch.migrations.bulkload.common.http.HttpResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class InvalidResponse extends RfsException {
    private static final Pattern UNKNOWN_SETTING = Pattern.compile("unknown setting \\[([a-zA-Z0-9_.-]+)\\].+");
    private static final Pattern AWARENESS_ATTRIBUTE_EXCEPTION = Pattern.compile("expected total copies needs to be a multiple of total awareness attributes");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final transient HttpResponse response;
    private static final String ERROR_STRING = "error";

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

            var errorBody = Optional.ofNullable(bodyNode).map(node -> node.get(ERROR_STRING));

            // Check high level cause
            errorBody.map(InvalidResponse::getUnknownSetting).ifPresent(interimResults::add);

            // Check root cause errors
            errorBody.map(node -> node.get("root_cause")).ifPresent(nodes ->
                nodes.forEach(
                    node -> Optional.of(node).map(InvalidResponse::getUnknownSetting).ifPresent(interimResults::add)
                )
            );

            // Check all suppressed errors
            errorBody.map(node -> node.get("suppressed")).ifPresent(nodes ->
                nodes.forEach(
                    node ->
                        Optional.of(node).map(InvalidResponse::getUnknownSetting).ifPresent(interimResults::add)
                ));

            var onlyExpectedErrors = interimResults.stream()
                .map(Entry::getKey)
                .allMatch(errorType -> "illegal_argument_exception".equals(errorType) || "settings_exception".equals(errorType));
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
            var matcher = UNKNOWN_SETTING.matcher(entry.getValue().asText());
            if (!matcher.matches()) {
                return null;
            }

            return Map.entry(entry.getKey().asText(), matcher.group(1));
        }).orElse(null);
    }

    /** Awareness attribute exceptions (when the replica count doesn't match the number of zones) present slightly differently
     in different versions. The message is the same (`"Validation Failed: 1: expected total copies needs to be a multiple of total awareness attributes [3];"`,
     for instance), but the type is different (either `validation_exception` or `illegal_argument_exception`). For this reason,
     we're matching based on a regex against the message instead of also checking the type.
    **/
    public Optional<String> containsAwarenessAttributeException() {
        try {
            var interimResults = new ArrayList<String>();
            var bodyNode = objectMapper.readTree(response.body);

            if (bodyNode != null && bodyNode.has(ERROR_STRING)) {
                JsonNode errorNode = bodyNode.get(ERROR_STRING);
                JsonNode rootCauses = errorNode.get("root_cause");

                if (rootCauses != null && rootCauses.isArray()) {
                    for (JsonNode cause : rootCauses) {
                        JsonNode reasonNode = cause.get("reason");
                        if (reasonNode != null && !reasonNode.isNull()) {
                            interimResults.add(reasonNode.textValue());
                        }
                    }
                }
            }
            interimResults = interimResults.stream().filter(AWARENESS_ATTRIBUTE_EXCEPTION.asPredicate()).collect(Collectors.toCollection(ArrayList::new));

            return interimResults.stream().findAny();

        } catch (Exception e) {
            log.warn("Error parsing error message to attempt recovery" + response.body, e);
            return Optional.empty();
        }    }
}
