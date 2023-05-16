package org.opensearch.migrations.transform;

import org.opensearch.migrations.replay.datahandlers.PayloadFaultMap;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonMessageWithFaultablePayload;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * This is an experimental JsonTransformer that is meant to perform basic URI and payload transformations
 * to excise index type mappings for relevant operations.
 */
public class TypeMappingJsonTransformer implements JsonTransformer {
    final static Pattern PATH3_CAPTURING_SIDES_PATTERN =
            Pattern.compile("^(\\/[^\\/]*)\\/[^\\/]*(\\/[^\\/]*)$");
    final static Pattern PATH1_CAPTURING_FIRST_PATTERN =
            Pattern.compile("^(\\/[^\\/]*)$");
    public static final String SEARCH_URI_COMPONENT = "/_search";
    public static final String DOC_URI_COMPONENT = "/_doc";
    public static final String MAPPINGS_KEYNAME = "mappings";

    @Override
    public Object transformJson(Object incomingJson) {
        if (!(incomingJson instanceof Map)) {
            return incomingJson;
        } else {
            return transformHttpMessage((Map) incomingJson);
        }
    }

    private Object transformHttpMessage(Map<String, Object> httpMsg) {
        var incomingMethod = httpMsg.get(HttpJsonMessageWithFaultablePayload.METHOD);
        if ("GET".equals(incomingMethod)) {
            processGet(httpMsg);
        } else if ("PUT".equals(incomingMethod)) {
            processPut(httpMsg);
        }
        return httpMsg;
    }

    private void processGet(Map<String, Object> httpMsg) {
        var incomingUri = (String) httpMsg.get(HttpJsonMessageWithFaultablePayload.URI);
        var matchedUri = PATH3_CAPTURING_SIDES_PATTERN.matcher(incomingUri);
        if (matchedUri.matches()) {
            var operationStr = matchedUri.group(2);
            if (operationStr.equals(SEARCH_URI_COMPONENT)) {
                httpMsg.put(HttpJsonMessageWithFaultablePayload.URI, matchedUri.group(1) + operationStr);
            }
        }
    }

    private void processPut(Map<String, Object> httpMsg) {
        final var uriStr = (String) httpMsg.get(HttpJsonMessageWithFaultablePayload.URI);
        var matchedTriple = PATH3_CAPTURING_SIDES_PATTERN.matcher(uriStr);
        if (matchedTriple.matches()) {
            httpMsg.put(HttpJsonMessageWithFaultablePayload.URI,
                    matchedTriple.group(1) + DOC_URI_COMPONENT + matchedTriple.group(2));
            return;
        }
        var matchedSingle = PATH1_CAPTURING_FIRST_PATTERN.matcher(uriStr);
        if (matchedSingle.matches()) {
            var topPayloadElement =
                    (Map<String, Object>) ((Map<String, Object>) httpMsg.get(HttpJsonMessageWithFaultablePayload.PAYLOAD))
                            .get(PayloadFaultMap.INLINED_JSON_BODY_DOCUMENT_KEY);
            var mappingsValue = (Map<String, Object>) topPayloadElement.get(MAPPINGS_KEYNAME);
            if (mappingsValue != null) {
                exciseMappingsType(topPayloadElement, mappingsValue);
            }
        }
    }

    private void exciseMappingsType(Map<String, Object> mappingsParent, Map<String, Object> mappingsValue) {
        var firstMappingOp = mappingsValue.entrySet().stream().findFirst();
        firstMappingOp.ifPresent(firstMapping -> {
            mappingsParent.clear();
            mappingsParent.put(MAPPINGS_KEYNAME, firstMapping.getValue());
        });
    }
}
