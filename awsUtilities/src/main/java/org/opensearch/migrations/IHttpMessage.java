package org.opensearch.migrations;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface IHttpMessage {
    String method();

    String path();

    String protocol();

    Map<String, List<String>> headers();

    default Optional<String> getFirstHeaderValueCaseInsensitive(String key) {
           return Optional.ofNullable(headers().get(key))
               .map(val -> val.get(0))
               .or(() -> {
                var lowerKey = key.toLowerCase();
                return headers().entrySet().stream().filter(
                        entry -> entry.getKey().equalsIgnoreCase(lowerKey)).findFirst()
                    .map(entry -> entry.getValue().get(0));});
    }
}
