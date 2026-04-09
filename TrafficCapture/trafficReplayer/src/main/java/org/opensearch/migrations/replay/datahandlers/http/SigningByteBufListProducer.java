package org.opensearch.migrations.replay.datahandlers.http;

import java.util.List;
import java.util.function.Function;

import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.ByteBufListProducer;
import org.opensearch.migrations.transform.IAuthTransformer;

import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCounted;
import lombok.extern.slf4j.Slf4j;

/**
 * A {@link ByteBufListProducer} that re-signs auth headers and re-serializes headers on each
 * {@link #get()} call. Body ByteBufs are pre-compressed and reused across invocations.
 * Only header serialization runs per attempt.
 */
@Slf4j
public class SigningByteBufListProducer extends ByteBufListProducer {
    private final HttpJsonRequestWithFaultingPayload templateHeaders;
    private final List<ByteBuf> bodyByteBufs;
    private final IAuthTransformer.SignatureProducer signatureProducer;
    private final List<List<Integer>> chunkSizes;
    private final Function<HttpJsonRequestWithFaultingPayload, ByteBufList> serializer;

    public SigningByteBufListProducer(
        HttpJsonRequestWithFaultingPayload templateHeaders,
        List<ByteBuf> bodyByteBufs,
        IAuthTransformer.SignatureProducer signatureProducer,
        List<List<Integer>> chunkSizes,
        Function<HttpJsonRequestWithFaultingPayload, ByteBufList> serializer
    ) {
        this.templateHeaders = templateHeaders;
        this.bodyByteBufs = bodyByteBufs;
        this.signatureProducer = signatureProducer;
        this.chunkSizes = chunkSizes;
        this.serializer = serializer;
    }

    @Override
    public int numByteBufs() {
        // Header ByteBufs: determined by chunkSizes.get(0) — one per entry, or 1 if single-entry/empty
        var headerChunkCount = chunkSizes.isEmpty() || chunkSizes.get(0).isEmpty()
            ? 1 : chunkSizes.get(0).size();
        return headerChunkCount + bodyByteBufs.size();
    }

    @Override
    public ByteBufList get() {
        var headers = HttpJsonTransformingConsumer.deepCopyHeaders(templateHeaders);
        var authHeaders = signatureProducer.signHeaders(headers);
        headers.headers().putAll(authHeaders);
        return serializer.apply(headers);
    }

    @Override
    protected void deallocate() {
        bodyByteBufs.forEach(ReferenceCounted::release);
        bodyByteBufs.clear();
    }
}
