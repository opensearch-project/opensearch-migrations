package org.opensearch.migrations.trafficcapture.model;

import com.google.protobuf.CodedOutputStream;

import java.util.concurrent.CompletableFuture;

public class OffloaderInput {

    private CodedOutputStream codedOutputStream;
    private CaptureOutputStreamMetadata captureOutputStreamMetadata;

    public OffloaderInput(CodedOutputStream codedOutputStream, CaptureOutputStreamMetadata captureOutputStreamMetadata) {
        this.codedOutputStream = codedOutputStream;
        this.captureOutputStreamMetadata = captureOutputStreamMetadata;
    }

    public CodedOutputStream getCodedOutputStream() {
        return codedOutputStream;
    }

    public CaptureOutputStreamMetadata getCaptureOutputStreamMetadata() {
        return captureOutputStreamMetadata;
    }
}
