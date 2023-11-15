package org.opensearch.migrations.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaders;
import lombok.Lombok;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.coreutils.MetricsAttributeKey;
import org.opensearch.migrations.coreutils.MetricsEvent;
import org.opensearch.migrations.coreutils.MetricsLogBuilder;
import org.opensearch.migrations.coreutils.MetricsLogger;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.TransformedPackets;
import org.opensearch.migrations.replay.datatypes.UniqueSourceRequestKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class SourceTargetCaptureTuple implements AutoCloseable {
    final UniqueSourceRequestKey uniqueRequestKey;
    final RequestResponsePacketPair sourcePair;
    final TransformedPackets targetRequestData;
    final List<byte[]> targetResponseData;
    final HttpRequestTransformationStatus transformationStatus;
    final Throwable errorCause;
    Duration targetResponseDuration;

    public SourceTargetCaptureTuple(@NonNull UniqueSourceRequestKey uniqueRequestKey,
                                    RequestResponsePacketPair sourcePair,
                                    TransformedPackets targetRequestData,
                                    List<byte[]> targetResponseData,
                                    HttpRequestTransformationStatus transformationStatus,
                                    Throwable errorCause,
                                    Duration targetResponseDuration) {
        this.uniqueRequestKey = uniqueRequestKey;
        this.sourcePair = sourcePair;
        this.targetRequestData = targetRequestData;
        this.targetResponseData = targetResponseData;
        this.transformationStatus = transformationStatus;
        this.errorCause = errorCause;
        this.targetResponseDuration = targetResponseDuration;
    }

    @Override
    public void close() {
        Optional.ofNullable(targetRequestData).ifPresent(d->d.close());
    }

    @Override
    public String toString() {
        return HttpByteBufFormatter.setPrintStyleFor(HttpByteBufFormatter.PacketPrintFormat.TRUNCATED, () -> {
            final StringJoiner sj = new StringJoiner("\n ", "SourceTargetCaptureTuple{","}");
            sj.add("diagnosticLabel=").add(uniqueRequestKey.toString());
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
}
