package org.opensearch.migrations.trafficcapture;

import java.time.Instant;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Timestamp;

import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import io.netty.buffer.ByteBuf;

/**
 * Utility functions for computing sizes of fields to be added to a CodedOutputStream
 */
public class CodedOutputStreamSizeUtil {

    /**
     * Static class
     */
    private CodedOutputStreamSizeUtil() {}

    public static int getSizeOfTimestamp(Instant t) {
        long seconds = t.getEpochSecond();
        int nanos = t.getNano();
        var secSize = CodedOutputStream.computeInt64Size(Timestamp.SECONDS_FIELD_NUMBER, seconds);
        var nanoSize = nanos == 0 ? 0 : CodedOutputStream.computeInt32Size(Timestamp.NANOS_FIELD_NUMBER, nanos);
        return secSize + nanoSize;
    }

    /**
     * This function calculates the maximum bytes that would be needed to store a [Read/Write]SegmentObservation, if constructed
     * from the given ByteBuf and associated segment field numbers and values passed in. This estimate is essentially
     * the max size needed in the CodedOutputStream to store the provided ByteBuf data and its associated TrafficStream
     * overhead. The actual required bytes could be marginally smaller.
     */
    public static int maxBytesNeededForASegmentedObservation(
        Instant timestamp,
        int observationFieldNumber,
        int dataFieldNumber,
        ByteBuf buf
    ) {
        // Timestamp required bytes
        int tsContentSize = getSizeOfTimestamp(timestamp);
        int tsTagAndContentSize = CodedOutputStream.computeInt32Size(TrafficObservation.TS_FIELD_NUMBER, tsContentSize)
            + tsContentSize;

        // Capture required bytes
        int dataSize = computeByteBufRemainingSize(dataFieldNumber, buf);
        int captureTagAndContentSize = CodedOutputStream.computeInt32Size(observationFieldNumber, dataSize) + dataSize;

        // Observation and closing index required bytes
        return bytesNeededForObservationAndClosingIndex(
            tsTagAndContentSize + captureTagAndContentSize,
            Integer.MAX_VALUE
        );
    }

    /**
     * This function determines the number of bytes needed to write the readable bytes in a byteBuf and its tag.
     */
    public static int computeByteBufRemainingSize(int fieldNumber, ByteBuf buf) {
        return CodedOutputStream.computeTagSize(fieldNumber) + computeByteBufRemainingSizeNoTag(buf);
    }

    /**
     * This function determines the number of bytes needed to write the readable bytes in a byteBuf.
     */
    public static int computeByteBufRemainingSizeNoTag(ByteBuf buf) {
        int bufSize = buf.readableBytes();
        return CodedOutputStream.computeUInt32SizeNoTag(bufSize) + bufSize;
    }

    /**
     * This function determines the number of bytes needed to store a TrafficObservation and a closing index for a
     * TrafficStream, from the provided input.
     */
    public static int bytesNeededForObservationAndClosingIndex(
        int observationContentSize,
        int numberOfTrafficStreamsSoFar
    ) {
        int observationTagSize = CodedOutputStream.computeUInt32Size(
            TrafficStream.SUBSTREAM_FIELD_NUMBER,
            observationContentSize
        );

        // Size for TrafficStream index added when flushing, use arbitrary field to calculate
        int indexSize = CodedOutputStream.computeInt32Size(
            TrafficStream.NUMBEROFTHISLASTCHUNK_FIELD_NUMBER,
            numberOfTrafficStreamsSoFar
        );

        return observationTagSize + observationContentSize + indexSize;
    }

}
