package org.opensearch.migrations.transform;

import org.opensearch.migrations.replay.datahandlers.PayloadAccessFaultingMap;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonMessageWithFaultingPayload;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * This is a JsonTransformer that is meant to remove header fields related to a sigV4 signature.
 */
public class SigV4ExcisionJsonTransformer implements JsonTransformer {

    public static final String AUTHORIZATION_KEYNAME = "Authorization";
    public static final String SECURITY_TOKEN_KEYNAME = "X-Amz-Security-Token";

    @Override
    public Object transformJson(Object incomingJson) {

        if (!(incomingJson instanceof Map)) {
            return incomingJson;
        } else {
            return transformHttpMessage((Map) incomingJson);
        }
    }

    private Object transformHttpMessage(Map<String, Object> httpMsg) {
        var headers = (Map) httpMsg.get(HttpJsonMessageWithFaultingPayload.HEADERS);
        headers.remove(AUTHORIZATION_KEYNAME);
        headers.remove(SECURITY_TOKEN_KEYNAME);
        return httpMsg;
    }
}