package org.opensearch.migrations.replay.datahandlers.http;

// REBUILD-LIMBO(G10) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Test carried byte-identical. Unresolved: GenerateRandomNestedJsonObject . Per AGENTS.md section 4 an inherited test may stay broken while the architectures are partly connected; this one is restored by the milestone that rebuilds its subject, keeping its assertions conceptually stable while changing the mechanics.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G10)
/*

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import org.opensearch.migrations.replay.GenerateRandomNestedJsonObject;
import org.opensearch.migrations.replay.ReplayUtils;
import org.opensearch.migrations.replay.datahandlers.PayloadAccessFaultingMap;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.transform.JsonKeysForHttpMessage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpContent;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@Slf4j
@WrapWithNettyLeakDetection
public class NettyJsonBodySerializeHandlerTest {
    @Test
    public void testJsonSerializerHandler() throws Exception {
        var randomJsonGenerator = new GenerateRandomNestedJsonObject();
        var randomJson = randomJsonGenerator.makeRandomJsonObject(new Random(2), 2, 1);
        var headers = new StrictCaseInsensitiveHttpHeadersMap();
        headers.put("content-type", List.of("application/json"));
        var fullHttpMessageWithJsonBody = new HttpJsonRequestWithFaultingPayload(headers);
        fullHttpMessageWithJsonBody.setPayloadFaultMap(new PayloadAccessFaultingMap(headers));
        fullHttpMessageWithJsonBody.payload().put(JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY, randomJson);

        var channel = new EmbeddedChannel(new NettyJsonBodySerializeHandler());
        channel.writeInbound(fullHttpMessageWithJsonBody);

        var handlerAccumulatedStream = ReplayUtils.byteBufsToInputStream(getByteBufStreamFromChannel(channel));

        String originalTreeStr = new ObjectMapper().writeValueAsString(randomJson);
        var reconstitutedTreeStr = new String(handlerAccumulatedStream.readAllBytes(), StandardCharsets.UTF_8);
        Assertions.assertEquals(originalTreeStr, reconstitutedTreeStr);

        getByteBufStreamFromChannel(channel).forEach(bb -> bb.release());
    }

    private static Stream<ByteBuf> getByteBufStreamFromChannel(EmbeddedChannel channel) {
        return channel.inboundMessages().stream().filter(x -> x instanceof HttpContent).map(x -> {
            var rval = ((HttpContent) x).content();
            log.info("refCnt=" + rval.refCnt() + " for " + x);
            return rval;
        });
    }
}

*/
// REBUILD-LIMBO-END(G10)