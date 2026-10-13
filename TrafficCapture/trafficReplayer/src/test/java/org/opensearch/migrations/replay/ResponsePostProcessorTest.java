package org.opensearch.migrations.replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.TransformationLoader;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ResponsePostProcessorTest {

    /**
     * Simulates the applyResponsePostProcessor logic from TrafficReplayerCore
     * to test it in isolation without needing the full pipeline.
     */
    @SuppressWarnings("unchecked")
    private static void applyResponsePostProcessor(
        IJsonTransformer responsePostProcessor, List<Map<String, Object>> targetResponseList
    ) {
        if (targetResponseList == null) return;
        for (int i = 0; i < targetResponseList.size(); i++) {
            var original = targetResponseList.get(i);
            if (original == null) continue;
            try {
                var transformed = (Map<String, Object>) responsePostProcessor.transformJson(original);
                targetResponseList.set(i, transformed);
            } catch (Exception e) {
                targetResponseList.set(i, null);
            }
        }
    }

    @Test
    void postProcessor_transformsResponseSuccessfully() {
        IJsonTransformer addField = input -> {
            @SuppressWarnings("unchecked")
            var map = (Map<String, Object>) input;
            map.put("postProcessed", true);
            return map;
        };

        var response = new LinkedHashMap<String, Object>(Map.of(
            "Status-Code", 200,
            "payload", Map.of("inlinedJsonBody", Map.of("hits", Map.of("total", 5)))
        ));
        var responses = new ArrayList<Map<String, Object>>();
        responses.add(response);

        applyResponsePostProcessor(addField, responses);

        Assertions.assertNotNull(responses.get(0));
        Assertions.assertEquals(true, responses.get(0).get("postProcessed"));
        Assertions.assertEquals(200, responses.get(0).get("Status-Code"));
    }

    @Test
    void postProcessor_failureSetResponseToNull() {
        IJsonTransformer failing = input -> { throw new RuntimeException("transform error"); };

        var response = new LinkedHashMap<String, Object>(Map.of("Status-Code", 200));
        var responses = new ArrayList<Map<String, Object>>();
        responses.add(response);

        applyResponsePostProcessor(failing, responses);

        Assertions.assertNull(responses.get(0));
    }

    @Test
    void postProcessor_handlesMultipleResponses() {
        IJsonTransformer addMarker = input -> {
            @SuppressWarnings("unchecked")
            var map = (Map<String, Object>) input;
            map.put("transformed", true);
            return map;
        };

        var responses = new ArrayList<Map<String, Object>>();
        responses.add(new LinkedHashMap<>(Map.of("Status-Code", 200)));
        responses.add(new LinkedHashMap<>(Map.of("Status-Code", 503)));
        responses.add(new LinkedHashMap<>(Map.of("Status-Code", 200)));

        applyResponsePostProcessor(addMarker, responses);

        for (var r : responses) {
            Assertions.assertNotNull(r);
            Assertions.assertEquals(true, r.get("transformed"));
        }
    }

    @Test
    void postProcessor_partialFailure_onlyFailedResponseIsNull() {
        IJsonTransformer failOn503 = input -> {
            @SuppressWarnings("unchecked")
            var map = (Map<String, Object>) input;
            if (Integer.valueOf(503).equals(map.get("Status-Code"))) {
                throw new RuntimeException("cannot transform error response");
            }
            map.put("transformed", true);
            return map;
        };

        var responses = new ArrayList<Map<String, Object>>();
        responses.add(new LinkedHashMap<>(Map.of("Status-Code", 200)));
        responses.add(new LinkedHashMap<>(Map.of("Status-Code", 503)));
        responses.add(new LinkedHashMap<>(Map.of("Status-Code", 200)));

        applyResponsePostProcessor(failOn503, responses);

        Assertions.assertNotNull(responses.get(0));
        Assertions.assertEquals(true, responses.get(0).get("transformed"));
        Assertions.assertNull(responses.get(1)); // failed
        Assertions.assertNotNull(responses.get(2));
        Assertions.assertEquals(true, responses.get(2).get("transformed"));
    }

    @Test
    void postProcessor_nullResponseList_noException() {
        IJsonTransformer transformer = input -> input;
        Assertions.assertDoesNotThrow(() -> applyResponsePostProcessor(transformer, null));
    }

    @Test
    void postProcessor_emptyResponseList_noException() {
        IJsonTransformer transformer = input -> input;
        var responses = new ArrayList<Map<String, Object>>();
        applyResponsePostProcessor(transformer, responses);
        Assertions.assertTrue(responses.isEmpty());
    }

    @Test
    void postProcessor_nullEntryInList_skipped() {
        IJsonTransformer transformer = input -> {
            @SuppressWarnings("unchecked")
            var map = (Map<String, Object>) input;
            map.put("touched", true);
            return map;
        };

        var responses = new ArrayList<Map<String, Object>>();
        responses.add(null);
        responses.add(new LinkedHashMap<>(Map.of("Status-Code", 200)));

        applyResponsePostProcessor(transformer, responses);

        Assertions.assertNull(responses.get(0)); // stayed null
        Assertions.assertEquals(true, responses.get(1).get("touched"));
    }

    @Test
    void postProcessor_canAccessResponseBody() {
        IJsonTransformer extractHits = input -> {
            @SuppressWarnings("unchecked")
            var map = (Map<String, Object>) input;
            var payload = (Map<String, Object>) map.get("payload");
            if (payload != null) {
                var body = (Map<String, Object>) payload.get("inlinedJsonBody");
                if (body != null) {
                    map.put("numFound", ((Map<?, ?>) body.get("hits")).get("total"));
                }
            }
            return map;
        };

        var body = new LinkedHashMap<String, Object>(Map.of(
            "hits", Map.of("total", Map.of("value", 42))
        ));
        var payload = new LinkedHashMap<String, Object>(Map.of("inlinedJsonBody", body));
        var response = new LinkedHashMap<String, Object>(Map.of(
            "Status-Code", 200,
            "payload", payload
        ));
        var responses = new ArrayList<Map<String, Object>>();
        responses.add(response);

        applyResponsePostProcessor(extractHits, responses);

        Assertions.assertNotNull(responses.get(0).get("numFound"));
    }

    // ─── Tests that exercise actual production code (for JaCoCo coverage) ────────

    private ParsedHttpMessagesAsDicts buildParsedMsgs(List<Map<String, Object>> responses) {
        var mockContext = Mockito.mock(
            org.opensearch.migrations.replay.tracing.IReplayContexts.ITupleHandlingContext.class);
        return new ParsedHttpMessagesAsDicts(mockContext,
            java.util.Optional.empty(), java.util.Optional.empty(), java.util.List.of(),
            java.util.Optional.empty(), responses);
    }

    @Test
    void production_applyResponsePostProcessor_transforms() {
        IJsonTransformer addField = input -> {
            @SuppressWarnings("unchecked")
            var map = (Map<String, Object>) input;
            map.put("processed", true);
            return map;
        };
        var responses = new ArrayList<Map<String, Object>>();
        responses.add(new LinkedHashMap<>(Map.of("Status-Code", 200)));
        var parsedMsgs = buildParsedMsgs(responses);

        TrafficReplayerCore.applyResponsePostProcessor(addField, parsedMsgs);

        Assertions.assertEquals(true, parsedMsgs.targetResponseList.get(0).get("processed"));
    }

    @Test
    void production_applyResponsePostProcessor_failureSetsNull() {
        IJsonTransformer failing = input -> { throw new RuntimeException("error"); };
        var responses = new ArrayList<Map<String, Object>>();
        responses.add(new LinkedHashMap<>(Map.of("Status-Code", 200)));
        var parsedMsgs = buildParsedMsgs(responses);

        TrafficReplayerCore.applyResponsePostProcessor(failing, parsedMsgs);

        Assertions.assertNull(parsedMsgs.targetResponseList.get(0));
    }

    @Test
    void production_applyResponsePostProcessor_emptyListSafe() {
        var parsedMsgs = buildParsedMsgs(new ArrayList<>());
        Assertions.assertDoesNotThrow(() ->
            TrafficReplayerCore.applyResponsePostProcessor(input -> input, parsedMsgs));
        Assertions.assertTrue(parsedMsgs.targetResponseList.isEmpty());
    }

    @Test
    void production_applyResponsePostProcessor_nullEntrySkipped() {
        IJsonTransformer t = input -> { ((Map<String, Object>) input).put("x", 1); return input; };
        var responses = new ArrayList<Map<String, Object>>();
        responses.add(null);
        responses.add(new LinkedHashMap<>(Map.of("k", "v")));
        var parsedMsgs = buildParsedMsgs(responses);

        TrafficReplayerCore.applyResponsePostProcessor(t, parsedMsgs);

        Assertions.assertNull(parsedMsgs.targetResponseList.get(0));
        Assertions.assertEquals(1, parsedMsgs.targetResponseList.get(1).get("x"));
    }

    @Test
    void production_configureResponsePostProcessor_nullConfig_noOp() {
        var loader = new TransformationLoader();
        Assertions.assertDoesNotThrow(() ->
            TrafficReplayer.configureResponsePostProcessor(null, loader, null));
    }

    @Test
    void production_configureResponsePostProcessor_blankConfig_noOp() {
        var loader = new TransformationLoader();
        Assertions.assertDoesNotThrow(() ->
            TrafficReplayer.configureResponsePostProcessor(null, loader, "   "));
    }
}
