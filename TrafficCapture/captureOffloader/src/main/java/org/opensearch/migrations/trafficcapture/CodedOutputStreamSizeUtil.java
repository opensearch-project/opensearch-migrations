package org.opensearch.migrations.trafficcapture;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Timestamp;
import org.opensearch.migrations.trafficcapture.protos.EndOfSegmentsIndication;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.nio.ByteBuffer;
import java.time.Instant;

/**
 * Utility functions for computing sizes of fields to be added to a CodedOutputStream
 */
public class CodedOutputStreamSizeUtil {

    public static int getSizeOfTimestamp(Instant t) {
        long seconds = t.getEpochSecond();
        int nanos = t.getNano();
        var secSize = CodedOutputStream.computeInt64Size(Timestamp.SECONDS_FIELD_NUMBER, seconds);
        var nanoSize = nanos == 0 ? 0 : CodedOutputStream.computeInt32Size(Timestamp.NANOS_FIELD_NUMBER, nanos);
        return secSize + nanoSize;
    }

    /**
     * This function calculates the maximum bytes needed to store a message ByteBuffer that needs to be segmented into
     * different ReadSegmentObservation or WriteSegmentObservation and its associated Traffic Stream overhead into a
     * CodedOutputStream. The actual required bytes could be marginally smaller.
     */
    public static int maxBytesNeededForSegmentedMessage(Instant timestamp, int observationFieldNumber, int dataFieldNumber,
        int dataCountFieldNumber, int dataCount,  ByteBuffer buffer, int flushes) {
        // Timestamp closure bytes
        int tsContentSize = getSizeOfTimestamp(timestamp);
        int tsClosureSize = CodedOutputStream.computeInt32Size(TrafficObservation.TS_FIELD_NUMBER, tsContentSize) + tsContentSize;

        // Capture closure bytes
        int dataSize = CodedOutputStream.computeByteBufferSize(dataFieldNumber, buffer);
        int dataCountSize = dataCountFieldNumber > 0 ? CodedOutputStream.computeInt32Size(dataCountFieldNumber, dataCount) : 0;
        int captureContentSize = dataSize + dataCountSize;
        int captureClosureSize = CodedOutputStream.computeInt32Size(observationFieldNumber, captureContentSize) + captureContentSize;

        // Observation tag and closure size needed bytes
        int observationTagAndClosureSize = CodedOutputStream.computeInt32Size(TrafficStream.SUBSTREAM_FIELD_NUMBER, tsClosureSize + captureClosureSize);

        // Size for additional SegmentEndObservation to signify end of segments
        int segmentEndBytes = bytesNeededForSegmentEndObservation(timestamp);

        // Size for closing index, use arbitrary field to calculate
        int indexSize = CodedOutputStream.computeInt32Size(TrafficStream.NUMBER_FIELD_NUMBER, flushes);

        return observationTagAndClosureSize + tsClosureSize + captureClosureSize + segmentEndBytes + indexSize;
    }

    public static int bytesNeededForSegmentEndObservation(Instant timestamp) {
        // Timestamp closure bytes
        int tsContentSize = getSizeOfTimestamp(timestamp);
        int tsClosureSize = CodedOutputStream.computeInt32Size(TrafficObservation.TS_FIELD_NUMBER, tsContentSize) + tsContentSize;

        // Capture closure bytes
        int captureClosureSize = CodedOutputStream.computeMessageSize(TrafficObservation.SEGMENTEND_FIELD_NUMBER, EndOfSegmentsIndication.getDefaultInstance());

        // Observation tag and closure size needed bytes
        int observationTagAndClosureSize = CodedOutputStream.computeInt32Size(TrafficStream.SUBSTREAM_FIELD_NUMBER, tsClosureSize + captureClosureSize);

        return observationTagAndClosureSize + tsClosureSize + captureClosureSize;
    }


}
