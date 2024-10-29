package org.opensearch.migrations.transform;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * This is an experimental JsonTransformer that is meant to perform basic URI and payload transformations
 * to excise index type mappings for relevant operations.
 */
public class JsonTypeMappingTransformer implements IJsonTransformer {
    /**
     * This is used to match a URI of the form /INDEX/TYPE/foo... so that it can be
     * transformed into /INDEX/foo...
     */
    static final Pattern TYPED_OPERATION_URI_PATTERN_WITH_SIDE_CAPTURES = Pattern.compile(
        "^(\\/[^\\/]*)\\/[^\\/]*(\\/[^\\/]*)$"
    );

    /**
     * This is used to match a URI of the form /foo...
     */
    static final Pattern SINGLE_LEVEL_OPERATION_PATTERN_WITH_CAPTURE = Pattern.compile("^(\\/[^\\/]*)$");
    public static final String SEARCH_URI_COMPONENT = "/_search";
    public static final String DOC_URI_COMPONENT = "/_doc";
    public static final String MAPPINGS_KEYNAME = "mappings";

    @Override
    public Map<String, Object> transformJson(Map<String, Object> incomingJson) {
        return transformHttpMessage(incomingJson);
    }

    private Map<String, Object> transformHttpMessage(Map<String, Object> httpMsg) {
        var incomingMethod = httpMsg.get(JsonKeysForHttpMessage.METHOD_KEY);
        if ("GET".equals(incomingMethod)) {
            processGet(httpMsg);
        } else if ("PUT".equals(incomingMethod)) {
            processPut(httpMsg);
        }
        return httpMsg;
    }

    private void processGet(Map<String, Object> httpMsg) {
        var incomingUri = (String) httpMsg.get(JsonKeysForHttpMessage.URI_KEY);
        var matchedUri = TYPED_OPERATION_URI_PATTERN_WITH_SIDE_CAPTURES.matcher(incomingUri);
        if (matchedUri.matches()) {
            var operationStr = matchedUri.group(2);
            if (operationStr.equals(SEARCH_URI_COMPONENT)) {
                httpMsg.put(JsonKeysForHttpMessage.URI_KEY, matchedUri.group(1) + operationStr);
            }
        }
    }

    private void processPut(Map<String, Object> httpMsg) {
        final var uriStr = (String) httpMsg.get(JsonKeysForHttpMessage.URI_KEY);
        var matchedTriple = TYPED_OPERATION_URI_PATTERN_WITH_SIDE_CAPTURES.matcher(uriStr);
        if (matchedTriple.matches()) {
            // TODO: Add support for multiple type mappings per index (something possible with
            // versions before ES7)
            httpMsg.put(
                JsonKeysForHttpMessage.URI_KEY,
                matchedTriple.group(1) + DOC_URI_COMPONENT + matchedTriple.group(2)
            );
            return;
        }
        var matchedSingle = SINGLE_LEVEL_OPERATION_PATTERN_WITH_CAPTURE.matcher(uriStr);
        if (matchedSingle.matches()) {
            var topPayloadElement = (Map<String, Object>) ((Map<String, Object>) httpMsg.get(
                JsonKeysForHttpMessage.PAYLOAD_KEY
            )).get(JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY);
            var mappingsValue = (Map<String, Object>) topPayloadElement.get(MAPPINGS_KEYNAME);
            if (mappingsValue != null) {
                exciseMappingsType(topPayloadElement, mappingsValue);
            }
        }
    }

    private void exciseMappingsType(Map<String, Object> mappingsParent, Map<String, Object> mappingsValue) {
        var firstMappingOp = mappingsValue.entrySet().stream().findFirst();
        firstMappingOp.ifPresent(firstMapping -> mappingsParent.put(MAPPINGS_KEYNAME, firstMapping.getValue()));
    }
}
