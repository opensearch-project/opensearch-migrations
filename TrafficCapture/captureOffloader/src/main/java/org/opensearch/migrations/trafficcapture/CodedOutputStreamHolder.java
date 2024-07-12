package org.opensearch.migrations.trafficcapture;

import com.google.protobuf.CodedOutputStream;

import lombok.NonNull;

public interface CodedOutputStreamHolder {

    /**
     * Returns the maximum number of bytes that can be written to the output stream before exceeding
     * its limit, or -1 if the stream has no defined limit.
     *
     * @return the byte limit of the output stream, or -1 if no limit exists.
     */
    int getOutputStreamBytesLimit();

    /**
     * Calculates the remaining space in the output stream based on the limit set by
     * {@link #getOutputStreamBytesLimit()}. If the limit is defined, this method returns
     * the difference between that limit and the number of bytes already written. If no
     * limit is defined, returns -1, indicating unbounded space.
     *
     * @return the number of remaining bytes that can be written before reaching the limit,
     * or -1 if the stream is unbounded.
     */
    default int getOutputStreamSpaceLeft() {
        var limit = getOutputStreamBytesLimit();
        return (limit != -1) ? limit - getOutputStream().getTotalBytesWritten() : -1;
    }

    @NonNull
    CodedOutputStream getOutputStream();
}
