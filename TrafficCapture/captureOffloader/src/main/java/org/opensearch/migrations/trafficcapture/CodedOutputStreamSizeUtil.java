package org.opensearch.migrations.trafficcapture;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Timestamp;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.nio.ByteBuffer;
import java.time.Instant;

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
     * from the given ByteBuffer and associated segment field numbers and values passed in. This estimate is essentially
     * the max size needed in the CodedOutputStream to store the provided ByteBuffer data and its associated TrafficStream
     * overhead. The actual required bytes could be marginally smaller.
     */
    public static int maxBytesNeededForASegmentedObservation(Instant timestamp, int observationFieldNumber,
                                                             int dataFieldNumber, ByteBuffer buffer) {
        // Timestamp required bytes
        int tsContentSize = getSizeOfTimestamp(timestamp);
        int tsTagAndContentSize = CodedOutputStream.computeInt32Size(TrafficObservation.TS_FIELD_NUMBER, tsContentSize) + tsContentSize;

        // Capture required bytes
        int dataSize = computeByteBufferRemainingSize(dataFieldNumber, buffer);
        int captureTagAndContentSize = CodedOutputStream.computeInt32Size(observationFieldNumber, dataSize) + dataSize;

        // Observation and closing index required bytes
        return bytesNeededForObservationAndClosingIndex(tsTagAndContentSize + captureTagAndContentSize,
                Integer.MAX_VALUE);
    }

    /**
     * This function determines the number of bytes needed to write the remaining bytes in a byteBuffer and its tag.
     * Use this over CodeOutputStream.computeByteBufferSize(int fieldNumber, ByteBuffer buffer) due to the latter
     * relying on the ByteBuffer capacity instead of limit in size calculation.
     */
    public static int computeByteBufferRemainingSize(int fieldNumber, ByteBuffer buffer) {
        return CodedOutputStream.computeTagSize(fieldNumber) + computeByteBufferRemainingSizeNoTag(buffer);
    }

    /**
     * This function determines the number of bytes needed to write the remaining bytes in a byteBuffer. Use this over
     * CodeOutputStream.computeByteBufferSizeNoTag(ByteBuffer buffer) due to the latter relying on the
     * ByteBuffer capacity instead of limit in size calculation.
     */
    public static int computeByteBufferRemainingSizeNoTag(ByteBuffer buffer) {
        int bufferSize = buffer.remaining();
        return CodedOutputStream.computeUInt32SizeNoTag(bufferSize) + bufferSize;
    }


    /**
     * This function determines the number of bytes needed to store a TrafficObservation and a closing index for a
     * TrafficStream, from the provided input.
     */
    public static int bytesNeededForObservationAndClosingIndex(int observationContentSize, int numberOfTrafficStreamsSoFar) {
        int observationTagSize = CodedOutputStream.computeUInt32Size(TrafficStream.SUBSTREAM_FIELD_NUMBER, observationContentSize);

        // Size for TrafficStream index added when flushing, use arbitrary field to calculate
        int indexSize = CodedOutputStream.computeInt32Size(TrafficStream.NUMBEROFTHISLASTCHUNK_FIELD_NUMBER, numberOfTrafficStreamsSoFar);

        return observationTagSize + observationContentSize + indexSize;
    }


}
