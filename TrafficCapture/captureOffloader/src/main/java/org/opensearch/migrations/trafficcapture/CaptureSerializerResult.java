package org.opensearch.migrations.trafficcapture;

import com.google.protobuf.CodedOutputStream;
import lombok.Getter;

@Getter
public class CaptureSerializerResult {

    private final CodedOutputStream codedOutputStream;
    private final int trafficStreamIndex;

    public CaptureSerializerResult(CodedOutputStream codedOutputStream, int trafficStreamIndex) {
        this.codedOutputStream = codedOutputStream;
        this.trafficStreamIndex = trafficStreamIndex;
    }

}
