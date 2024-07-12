package org.opensearch.migrations.replay.datahandlers.http;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import org.opensearch.migrations.replay.GenerateRandomNestedJsonObject;
import org.opensearch.migrations.replay.ReplayUtils;
import org.opensearch.migrations.replay.datahandlers.PayloadAccessFaultingMap;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.transform.IHttpMessage;
import org.opensearch.migrations.transform.JsonKeysForHttpMessage;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpContent;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@WrapWithNettyLeakDetection
public class NettyJsonBodySerializeHandlerTest {
    @Test
    public void testJsonSerializerHandler() throws Exception {
        var randomJsonGenerator = new GenerateRandomNestedJsonObject();
        var randomJson = randomJsonGenerator.makeRandomJsonObject(new Random(2), 2, 1);
        var headers = new StrictCaseInsensitiveHttpHeadersMap();
        headers.put(IHttpMessage.CONTENT_TYPE, List.of(IHttpMessage.APPLICATION_JSON));
        var fullHttpMessageWithJsonBody = new HttpJsonMessageWithFaultingPayload(headers);
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
