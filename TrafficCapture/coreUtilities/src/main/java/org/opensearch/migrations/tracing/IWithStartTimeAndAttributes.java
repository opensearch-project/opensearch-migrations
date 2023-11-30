package org.opensearch.migrations.tracing;

public interface IWithStartTimeAndAttributes<T extends IWithAttributes> extends IWithStartTime, IWithAttributes<T> {
}
