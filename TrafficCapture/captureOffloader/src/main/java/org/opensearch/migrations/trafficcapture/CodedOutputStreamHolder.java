package org.opensearch.migrations.trafficcapture;

import com.google.protobuf.CodedOutputStream;
import lombok.NonNull;

public interface CodedOutputStreamHolder {
    @NonNull CodedOutputStream getOutputStream();
}
