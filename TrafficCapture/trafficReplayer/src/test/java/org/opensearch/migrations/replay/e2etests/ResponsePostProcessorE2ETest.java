package org.opensearch.migrations.replay.e2etests;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.testutils.SimpleHttpResponse;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.TransformationLoader;

import io.netty.handler.codec.http.HttpHeaderNames;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;


@Tag("longTest")
class ResponsePostProcessorE2ETest extends FullTrafficReplayerTest {

    @Test
    void deployedPostProcessorTransformsTheTupleResponse() throws Exception {
        var result = replayWithPostProcessor(input -> {
            @SuppressWarnings("unchecked")
            var response = new LinkedHashMap<>((Map<String, Object>) input);
            response.put("_postprocessed", true);
            return response;
        });

        var response = firstTargetResponse(result);
        Assertions.assertEquals(true, response.get("_postprocessed"));
        Assertions.assertTrue(result.fatalFailures().isEmpty());
    }

    @Test
    void postProcessorFailureLeavesOnlyThatResponseEmptyAndStillCommits()
        throws Exception {
        var result = replayWithPostProcessor(input -> {
            throw new IllegalStateException("test post-processor failure");
        });

        var targetResponses = targetResponses(result);
        Assertions.assertEquals(1, targetResponses.size());
        Assertions.assertNull(targetResponses.get(0));
        Assertions.assertEquals(
            1L,
            result.committedOffsets().get(TOPIC_PARTITION).offset()
        );
        Assertions.assertTrue(result.fatalFailures().isEmpty());
    }

    private static ReplayResult replayWithPostProcessor(
        IJsonTransformer postProcessor
    ) throws Exception {
        try (var server = SimpleNettyHttpServer.makeServer(false, request ->
            new SimpleHttpResponse(
                Map.of(HttpHeaderNames.CONTENT_LENGTH.toString(), "2"),
                "OK".getBytes(StandardCharsets.UTF_8),
                "OK",
                200
            )
        )) {
            return replayOne(
                server.localhostEndpoint(),
                requestResponseAndClose("GET /postprocess HTTP/1.1\r\nHost: source\r\n\r\n"),
                () -> new TransformationLoader()
                    .getTransformerFactoryLoaderWithNewHostName("localhost"),
                () -> postProcessor
            );
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> targetResponses(
        ReplayResult result
    ) {
        return (List<Map<String, Object>>) result.tuples()
            .get(0)
            .get("targetResponses");
    }

    private static Map<String, Object> firstTargetResponse(ReplayResult result) {
        var responses = targetResponses(result);
        Assertions.assertEquals(1, responses.size());
        return responses.get(0);
    }
}
