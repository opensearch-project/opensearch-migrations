package org.opensearch.migrations.replay;


import org.opensearch.migrations.replay.datahandlers.PayloadAccessFaultingMap;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonRequestWithFaultingPayload;
import org.opensearch.migrations.replay.datahandlers.http.ListKeyAdaptingCaseInsensitiveHeadersMap;
import org.opensearch.migrations.replay.datahandlers.http.StrictCaseInsensitiveHttpHeadersMap;
import org.opensearch.migrations.transform.TransformationLoader;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class PayloadNotFoundTest {
    @Test
    public void testTransformsPropagateExceptionProperly() throws JsonProcessingException {
        final HttpJsonRequestWithFaultingPayload FAULTING_MAP = new HttpJsonRequestWithFaultingPayload();
        FAULTING_MAP.setMethod("PUT");
        FAULTING_MAP.setPath("/_bulk");
        FAULTING_MAP.setHeaders(new ListKeyAdaptingCaseInsensitiveHeadersMap(new StrictCaseInsensitiveHttpHeadersMap()));
        FAULTING_MAP.headers().put("Content-Type", "application/json");
        var payloadMap = new PayloadAccessFaultingMap(FAULTING_MAP.headers().asStrictMap());
        FAULTING_MAP.setPayloadFaultMap(payloadMap);
        payloadMap.setDisableThrowingPayloadNotLoaded(false);
        final String EXPECTED = "{\n"
            + "  \"method\": \"PUT\",\n"
            + "  \"URI\": \"/_bulk\",\n"
            + "  \"headers\": {\n"
            + "      \"Content-Type\": \"application/json\"\n"
            + "  }\n"
            + "}\n";

        var transformer = new TransformationLoader().getTransformerFactoryLoader("newhost", null,
            "[{\"TypeMappingSanitizationTransformerProvider\":\"\"}]");
        var e = Assertions.assertThrows(Exception.class,
            () -> transformer.transformJson(FAULTING_MAP));
        Assertions.assertTrue(((PayloadAccessFaultingMap)FAULTING_MAP.payload()).missingPayloadWasAccessed());
    }
}
