/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.sink.TupleWriter;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.TransformationLoader;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ResponsePostProcessorTest {

    @Test
    void deployedWorkerPostProcessesEveryTargetResponseBeforeTupleTransformation() throws Exception {
        var tupleTransformCalls = new AtomicInteger();
        var postProcessCalls = new AtomicInteger();
        var tupleTransformerCloses = new AtomicInteger();
        var responseTransformerCloses = new AtomicInteger();
        var transformer = TrafficReplayerTopLevel.deployedTupleTransformer(
            () -> new IJsonTransformer() {
                @Override
                public Object transformJson(Object incomingJson) {
                    tupleTransformCalls.incrementAndGet();
                    @SuppressWarnings("unchecked")
                    var incomingTuple = (Map<String, Object>) incomingJson;
                    @SuppressWarnings("unchecked")
                    var incomingResponses =
                        (List<Map<String, Object>>) incomingTuple.get("targetResponses");
                    Assertions.assertEquals(2, incomingResponses.size());
                    Assertions.assertTrue((Boolean) incomingResponses.get(0).get("processed"));
                    Assertions.assertTrue((Boolean) incomingResponses.get(1).get("processed"));
                    return incomingJson;
                }

                @Override
                public void close() {
                    tupleTransformerCloses.incrementAndGet();
                }
            },
            () -> new IJsonTransformer() {
                @Override
                public Object transformJson(Object incomingJson) {
                    postProcessCalls.incrementAndGet();
                    @SuppressWarnings("unchecked")
                    var response = new LinkedHashMap<>((Map<String, Object>) incomingJson);
                    response.put("processed", true);
                    return response;
                }

                @Override
                public void close() {
                    responseTransformerCloses.incrementAndGet();
                }
            }
        );
        var tuple = new LinkedHashMap<String, Object>();
        tuple.put(
            "targetResponses",
            List.of(
                Map.of("Status-Code", 200),
                Map.of("Status-Code", 503)
            )
        );

        var result = transformer.transform(null, tuple);

        var transformed = Assertions.assertInstanceOf(
            TupleWriter.TransformedTuple.class,
            result
        );
        @SuppressWarnings("unchecked")
        var transformedMap = (Map<String, Object>) transformed.tuple();
        @SuppressWarnings("unchecked")
        var responses = (List<Map<String, Object>>) transformedMap.get("targetResponses");
        Assertions.assertEquals(2, postProcessCalls.get());
        Assertions.assertEquals(1, tupleTransformCalls.get());
        Assertions.assertTrue((Boolean) responses.get(0).get("processed"));
        Assertions.assertTrue((Boolean) responses.get(1).get("processed"));

        transformer.close();
        Assertions.assertEquals(1, tupleTransformerCloses.get());
        Assertions.assertEquals(1, responseTransformerCloses.get());
    }

    @Test
    void failedResponsePostProcessingLeavesOnlyThatResponseEmpty() {
        IJsonTransformer postProcessor = input -> {
            @SuppressWarnings("unchecked")
            var response = (Map<String, Object>) input;
            if (Integer.valueOf(503).equals(response.get("Status-Code"))) {
                throw new IllegalStateException("cannot transform 503");
            }
            return response;
        };
        var tuple = new LinkedHashMap<String, Object>();
        var responses = new ArrayList<Map<String, Object>>();
        responses.add(new LinkedHashMap<>(Map.of("Status-Code", 200)));
        responses.add(new LinkedHashMap<>(Map.of("Status-Code", 503)));
        responses.add(null);
        tuple.put("targetResponses", responses);

        var transformed = TrafficReplayerTopLevel.postProcessTargetResponses(
            postProcessor,
            tuple
        );

        @SuppressWarnings("unchecked")
        var transformedResponses = (List<Map<String, Object>>) transformed.get(
            "targetResponses"
        );
        Assertions.assertNotNull(transformedResponses.get(0));
        Assertions.assertNull(transformedResponses.get(1));
        Assertions.assertNull(transformedResponses.get(2));
        Assertions.assertSame(responses, tuple.get("targetResponses"));
    }

    @Test
    void absentResponsePostProcessorConfigurationIsARealNoOp() {
        var loader = new TransformationLoader();
        Assertions.assertNull(
            TrafficReplayer.buildResponsePostProcessorSupplier(loader, null)
        );
        Assertions.assertNull(
            TrafficReplayer.buildResponsePostProcessorSupplier(loader, "  ")
        );
    }
}
