package org.opensearch.migrations.trafficcapture.kafkaoffloader.tracing;

import org.opensearch.migrations.tracing.IInstrumentConstructor;
import org.opensearch.migrations.tracing.commoncontexts.IConnectionContext;

public interface IRootKafkaOffloaderContext extends IInstrumentConstructor {
    KafkaRecordContext.MetricInstruments getKafkaOffloadingInstruments();

    CapturePublishingMetrics getCapturePublishingMetrics();

    default KafkaRecordContext createKafkaRecordContext(
        IConnectionContext telemetryContext,
        String topicNameForTraffic,
        String recordId,
        int length
    ) {
        return new KafkaRecordContext(this, telemetryContext, topicNameForTraffic, recordId, length);
    }
}
