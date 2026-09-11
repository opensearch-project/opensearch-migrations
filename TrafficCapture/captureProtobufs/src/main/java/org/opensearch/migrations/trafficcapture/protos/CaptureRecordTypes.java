package org.opensearch.migrations.trafficcapture.protos;

public final class CaptureRecordTypes {
    public static final String RECORD_TYPE_HEADER = "opensearch-capture-record-type";
    public static final String WRITER_NODE_ID_HEADER = "opensearch-capture-writer-node-id";
    public static final String TRAFFIC_RECORD_TYPE = "traffic-stream-v1";
    public static final String LIVENESS_RECORD_TYPE = "proxy-liveness-v1";
    public static final String NO_MORE_WRITES_RECORD_TYPE = "proxy-no-more-writes-v1";
    public static final String CAPABILITY_PROBE_RECORD_TYPE = "capture-capability-probe-v1";

    private CaptureRecordTypes() {}
}
