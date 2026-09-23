package org.opensearch.migrations.replay.datahandlers.http;

// REBUILD-LIMBO(G5) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Cascade from the left-behind legacy set. Unresolved: HttpJsonTransformingConsumer . Carried byte-identical so the behaviour stays enumerable; its milestone strips the legacy references and un-marks it.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G5)
/*

import java.util.List;
import java.util.function.Function;

import org.opensearch.migrations.replay.datatypes.AttemptPayload;
import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.ByteBufListProducer;
import org.opensearch.migrations.replay.datatypes.DiagnosticPayload;
import org.opensearch.migrations.transform.IAuthTransformer;

import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCounted;
import lombok.extern.slf4j.Slf4j;

*/
// REBUILD-LIMBO-END(G5)
/**
 * A {@link ByteBufListProducer} that re-signs auth headers and re-serializes headers on each
 * {@link #get()} call. Body ByteBufs are pre-compressed and reused across invocations.
 * Only header serialization runs per attempt.
 */
// REBUILD-LIMBO-START(G5)
/*
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
    public AttemptPayload newAttempt() {
        return AttemptPayload.owned(get(), ownershipMetrics());
    }

    @Override
    public DiagnosticPayload retainDiagnosticCopy() {
        return new DiagnosticPayload(get(), ownershipMetrics());
    }

    @Override
    protected int ownedBufferCount() {
        return bodyByteBufs.size();
    }

    @Override
    protected long ownedBytes() {
        return bodyByteBufs.stream().mapToLong(ByteBuf::readableBytes).sum();
    }

    @Override
    protected void deallocate() {
        bodyByteBufs.forEach(ReferenceCounted::release);
        bodyByteBufs.clear();
    }
}

*/
// REBUILD-LIMBO-END(G5)