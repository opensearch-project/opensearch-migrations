package org.opensearch.migrations.coreutils;

// These enum values create a discrete set of potential attributes that
// are attached to metrics events, which allows comparison across different events (when appropriate)
// and building reliable visualizations and dashboards off of this data. If an attribute key is changed
// or removed, one should expect that downstream dependencies (events that are being logged & dashboards/visualizations)
// will need to be updated as well, and may no longer be compatible with previously collected data. Adding values
// that correspond to attributes for new event types is backwards compatible.
public enum MetricsAttributeKey {
    EVENT("event"),
    CONNECTION_ID("connection_id"),
    REQUEST_ID("request_id"),
    CHANNEL_ID("channel_id"),
    SIZE_IN_BYTES("size_in_bytes"),
    TOPIC_NAME("topic_name"),
    SCHEDULED_SEND_TIME("scheduled_send_time"),
    DELAY_FROM_ORIGINAL_TO_SCHEDULED_START("delayFromOriginalToScheduledStartInMs"),

    HTTP_METHOD("http.method"),
    HTTP_ENDPOINT("http.endpoint"),
    HTTP_STATUS("http.status"),

    EXCEPTION_MESSAGE("exception.message"),
    EXCEPTION_STACKTRACE("exception.stacktrace"),
    EXCEPTION_TYPE( "exception.type");

    private String keyName;

    MetricsAttributeKey(String keyName) {
        this.keyName = keyName;
    }

    public String getKeyName() {
        return keyName;
    }
}
