package org.opensearch.migrations.replay;

import java.time.Duration;
import java.util.AbstractMap;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.DiagnosticPayload;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.tracing.IReplayContexts;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SourceTargetCaptureTuple implements AutoCloseable {
    @AllArgsConstructor
    @Getter
    public static class Response {
        List<byte[]> targetResponseData;
        Throwable errorCause;
        Duration targetResponseDuration;

        public String toString() {
            final var sj = new StringJoiner("\n");
            if (targetResponseDuration != null) {
                sj.add("targetResponseDuration=").add(targetResponseDuration + "");
            }
            Optional.ofNullable(targetResponseData)
                .filter(d -> !d.isEmpty())
                .ifPresent(
                    d -> sj.add("targetResponseData=")
                        .add(
                            HttpByteBufFormatter.httpPacketBytesToString(
                                HttpByteBufFormatter.HttpMessageType.RESPONSE,
                                d,
                                HttpByteBufFormatter.LF_LINE_DELIMITER
                            )
                        )
                );
            sj.add("errorCause=").add(errorCause == null ? "none" : errorCause.toString());
            return sj.toString();
        }
    }

    public final RequestResponsePacketPair sourcePair;
    public final ByteBufList targetRequestData;
    public final HttpRequestTransformationStatus transformationStatus;
    public final IReplayContexts.ITupleHandlingContext context;
    public final List<Response> responseList;
    public final Throwable topLevelErrorCause;
    private final DiagnosticPayload targetRequestPayload;

    public SourceTargetCaptureTuple(
        @NonNull IReplayContexts.ITupleHandlingContext tupleHandlingContext,
        RequestResponsePacketPair sourcePair,
        TransformedTargetRequestAndResponseList transformedTargetRequestAndResponseList,
        Throwable topLevelErrorCause
    ) {
        this.context = tupleHandlingContext;
        this.sourcePair = sourcePair;
        var transformationStatus = transformedTargetRequestAndResponseList == null ? null :
            transformedTargetRequestAndResponseList.getTransformationStatus();
        var responseList = transformedTargetRequestAndResponseList == null ? List.<Response>of() :
            transformedTargetRequestAndResponseList.responses().stream()
            .map(arr -> new Response(arr.packets.stream().map(AbstractMap.SimpleEntry::getValue)
                .collect(Collectors.toList()), arr.error, arr.duration))
            .collect(Collectors.toList());
        var targetRequestPayload = transformedTargetRequestAndResponseList == null ? null :
            transformedTargetRequestAndResponseList.claimDiagnosticPayload();
        ByteBufList targetRequestData;
        try {
            targetRequestData = targetRequestPayload == null ? null : targetRequestPayload.packets();
        } catch (Throwable t) {
            if (targetRequestPayload != null) {
                targetRequestPayload.close();
            }
            throw t;
        }
        this.targetRequestPayload = targetRequestPayload;
        this.targetRequestData = targetRequestData;
        this.transformationStatus = transformationStatus;
        this.responseList = responseList;
        this.topLevelErrorCause = topLevelErrorCause;
    }

    @Override
    public void close() {
        Optional.ofNullable(targetRequestPayload).ifPresent(DiagnosticPayload::close);
    }

    @Override
    public String toString() {
        return HttpByteBufFormatter.setPrintStyleFor(HttpByteBufFormatter.PacketPrintFormat.TRUNCATED, () -> {
            final StringJoiner sj = new StringJoiner("\n ", "SourceTargetCaptureTuple{", "}");
            sj.add("diagnosticLabel=").add(context.toString());
            if (sourcePair != null) {
                sj.add("sourcePair=").add(sourcePair.toString());
            }
            sj.add("transformStatus=").add(transformationStatus + "");
            Optional.ofNullable(targetRequestData)
                .ifPresent(
                    d -> sj.add("targetRequestData=")
                        .add(
                            d.isClosed()
                                ? "CLOSED"
                                : HttpByteBufFormatter.httpPacketBufsToString(
                                    HttpByteBufFormatter.HttpMessageType.REQUEST,
                                    d.streamUnretained(),
                                HttpByteBufFormatter.LF_LINE_DELIMITER
                                )
                        )
                );
            var counter = new AtomicInteger();
            responseList.forEach(r -> sj.add("response_"+counter.incrementAndGet()).add(r.toString()));
            return sj.toString();
        });
    }

    public UniqueReplayerRequestKey getRequestKey() {
        return context.getLogicalEnclosingScope().getReplayerRequestKey();
    }
}
