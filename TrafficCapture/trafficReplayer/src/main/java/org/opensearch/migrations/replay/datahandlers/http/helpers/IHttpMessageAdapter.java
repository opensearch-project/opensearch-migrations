package org.opensearch.migrations.replay.datahandlers.http.helpers;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.IHttpMessage;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonMessageWithFaultingPayload;
import org.opensearch.migrations.transform.JsonKeysForHttpMessage;

public class IHttpMessageAdapter {
    public static IHttpMessage toIHttpMessage(HttpJsonMessageWithFaultingPayload message) {
        return new IHttpMessage() {
            @Override
            public String method() {
                return (String) message.get(JsonKeysForHttpMessage.METHOD_KEY);
            }

            @Override
            public String path() {
                return (String) message.get(JsonKeysForHttpMessage.URI_KEY);
            }

            @Override
            public String protocol() {
                return (String) message.get(JsonKeysForHttpMessage.PROTOCOL_KEY);
            }

            @Override
            public Map<String, List<String>> headers() {
                        Map<String, Object> originalHeaders = message.headers();
                        Map<String, List<String>> convertedHeaders = new LinkedHashMap<>();

                    for (Map.Entry<String, Object> entry : originalHeaders.entrySet()) {
                        if (entry.getValue() instanceof List) {
                            @SuppressWarnings("unchecked")
                            List<String> values = (List<String>) entry.getValue();
                            convertedHeaders.put(entry.getKey(), values);
                        } else if (entry.getValue() != null) {
                            convertedHeaders.put(entry.getKey(), Collections.singletonList(entry.getValue().toString()));
                        }
                    }

                    return Collections.unmodifiableMap(convertedHeaders);

            }
        };
    }
}
