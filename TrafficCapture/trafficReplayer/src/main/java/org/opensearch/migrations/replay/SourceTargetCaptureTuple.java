package org.opensearch.migrations.replay;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.TransformedPackets;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.datatypes.UniqueSourceRequestKey;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.ReplayContexts;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

@Slf4j
public class SourceTargetCaptureTuple implements AutoCloseable {
    final RequestResponsePacketPair sourcePair;
    final TransformedPackets targetRequestData;
    final List<byte[]> targetResponseData;
    final HttpRequestTransformationStatus transformationStatus;
    final Throwable errorCause;
    Duration targetResponseDuration;
    final IReplayContexts.ITupleHandlingContext context;

    public SourceTargetCaptureTuple(@NonNull IReplayContexts.ITupleHandlingContext tupleHandlingContext,
                                    RequestResponsePacketPair sourcePair,
                                    TransformedPackets targetRequestData,
                                    List<byte[]> targetResponseData,
                                    HttpRequestTransformationStatus transformationStatus,
                                    Throwable errorCause,
                                    Duration targetResponseDuration) {
        this.context = tupleHandlingContext;
        this.sourcePair = sourcePair;
        this.targetRequestData = targetRequestData;
        this.targetResponseData = targetResponseData;
        this.transformationStatus = transformationStatus;
        this.errorCause = errorCause;
        this.targetResponseDuration = targetResponseDuration;
    }

    @Override
    public void close() {
        Optional.ofNullable(targetRequestData).ifPresent(TransformedPackets::close);
    }

    @Override
    public String toString() {
        return HttpByteBufFormatter.setPrintStyleFor(HttpByteBufFormatter.PacketPrintFormat.TRUNCATED, () -> {
            final StringJoiner sj = new StringJoiner("\n ", "SourceTargetCaptureTuple{","}");
            sj.add("diagnosticLabel=").add(context.toString());
            if (sourcePair != null) { sj.add("sourcePair=").add(sourcePair.toString()); }
            if (targetResponseDuration != null) { sj.add("targetResponseDuration=").add(targetResponseDuration+""); }
            Optional.ofNullable(targetRequestData).ifPresent(d-> sj.add("targetRequestData=")
                    .add(d.isClosed() ? "CLOSED" : HttpByteBufFormatter.httpPacketBufsToString(
                            HttpByteBufFormatter.HttpMessageType.REQUEST, d.streamUnretained(), false)));
            Optional.ofNullable(targetResponseData).filter(d->!d.isEmpty()).ifPresent(d -> sj.add("targetResponseData=")
                    .add(HttpByteBufFormatter.httpPacketBytesToString(HttpByteBufFormatter.HttpMessageType.RESPONSE, d)));
            sj.add("transformStatus=").add(transformationStatus+"");
            sj.add("errorCause=").add(errorCause == null ? "none" : errorCause.toString());
            return sj.toString();
        });
    }

    public UniqueReplayerRequestKey getRequestKey() {
        return context.getLogicalEnclosingScope().getReplayerRequestKey();
    }
}
