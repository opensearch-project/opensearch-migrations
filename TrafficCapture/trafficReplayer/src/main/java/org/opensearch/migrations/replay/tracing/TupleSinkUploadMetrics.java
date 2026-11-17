package org.opensearch.migrations.replay.tracing;

import java.io.IOException;
import java.util.Locale;

import org.opensearch.migrations.s3sink.RotatingGzipS3ObjectWriter;

import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkServiceException;

/**
 * Replay-specific metrics and bounded operational logs for inner S3 object-upload retries.
 */
@Slf4j
public final class TupleSinkUploadMetrics implements RotatingGzipS3ObjectWriter.UploadRetryObserver {
    public static final class MetricNames {
        private MetricNames() {}

        public static final String RETRY_ATTEMPTS = "tupleSinkUploadRetryAttempts";
        public static final String RETRYING_OBJECTS = "tupleSinkUploadsRetrying";
        public static final String RECOVERIES = "tupleSinkUploadRecoveries";
    }

    private final LongCounter retryAttempts;
    private final LongUpDownCounter retryingObjects;
    private final LongCounter recoveries;

    public TupleSinkUploadMetrics(@NonNull Meter meter) {
        retryAttempts = meter.counterBuilder(MetricNames.RETRY_ATTEMPTS)
            .setDescription("S3 object upload failures followed by a same-key retry")
            .setUnit("retries")
            .build();
        retryingObjects = meter.upDownCounterBuilder(MetricNames.RETRYING_OBJECTS)
            .setDescription("S3 tuple objects awaiting a successful same-key retry")
            .setUnit("objects")
            .build();
        recoveries = meter.counterBuilder(MetricNames.RECOVERIES)
            .setDescription("S3 tuple objects made durable after one or more failed upload attempts")
            .setUnit("objects")
            .build();
    }

    @Override
    public void retryScheduled(RotatingGzipS3ObjectWriter.UploadRetryFailure failure) {
        retryAttempts.add(1);
        if (failure.firstFailureForObject()) {
            retryingObjects.add(1);
        }
        var failureClass = classify(failure.failure());
        var exceptionType = exceptionType(failure.failure());
        if (failure.writerBecameDegraded()) {
            log.atWarn()
                .setCause(failure.failure())
                .setMessage(
                    "event=tuple_sink_upload_degraded bucket={} key={} attempt={} "
                        + "retry_delay_ms={} failure_class={} exception_type={} retrying_objects={}"
                )
                .addArgument(failure.bucket())
                .addArgument(failure.key())
                .addArgument(failure.failedAttempt())
                .addArgument(failure.retryDelay()::toMillis)
                .addArgument(failureClass)
                .addArgument(exceptionType)
                .addArgument(failure.retryingObjectCount())
                .log();
        } else {
            log.atDebug()
                .setCause(failure.failure())
                .setMessage(
                    "event=tuple_sink_upload_retry_failed bucket={} key={} attempt={} "
                        + "retry_delay_ms={} failure_class={} exception_type={} retrying_objects={}"
                )
                .addArgument(failure.bucket())
                .addArgument(failure.key())
                .addArgument(failure.failedAttempt())
                .addArgument(failure.retryDelay()::toMillis)
                .addArgument(failureClass)
                .addArgument(exceptionType)
                .addArgument(failure.retryingObjectCount())
                .log();
        }
    }

    @Override
    public void uploadRecovered(RotatingGzipS3ObjectWriter.UploadRetryRecovery recovery) {
        retryingObjects.add(-1);
        recoveries.add(1);
        if (recovery.writerRecovered()) {
            log.atInfo()
                .setMessage(
                    "event=tuple_sink_upload_recovered bucket={} key={} successful_attempt={} "
                        + "failed_attempts={} retrying_objects={}"
                )
                .addArgument(recovery.bucket())
                .addArgument(recovery.key())
                .addArgument(recovery.successfulAttempt())
                .addArgument(recovery.failedAttemptCount())
                .addArgument(recovery.retryingObjectCount())
                .log();
        } else {
            log.atDebug()
                .setMessage(
                    "event=tuple_sink_upload_object_recovered bucket={} key={} successful_attempt={} "
                        + "failed_attempts={} retrying_objects={}"
                )
                .addArgument(recovery.bucket())
                .addArgument(recovery.key())
                .addArgument(recovery.successfulAttempt())
                .addArgument(recovery.failedAttemptCount())
                .addArgument(recovery.retryingObjectCount())
                .log();
        }
    }

    @Override
    public void retryTerminated(RotatingGzipS3ObjectWriter.UploadRetryTermination termination) {
        retryingObjects.add(-1);
        log.atError()
            .setCause(termination.failure())
            .setMessage(
                "event=tuple_sink_upload_retry_terminated bucket={} key={} failed_attempts={} "
                    + "failure_class={} exception_type={} retrying_objects={}"
            )
            .addArgument(termination.bucket())
            .addArgument(termination.key())
            .addArgument(termination.failedAttemptCount())
            .addArgument(classify(termination.failure()))
            .addArgument(exceptionType(termination.failure()))
            .addArgument(termination.retryingObjectCount())
            .log();
    }

    private static String classify(Throwable failure) {
        if (failure instanceof SdkServiceException serviceFailure) {
            var statusCode = serviceFailure.statusCode();
            if (statusCode == 401 || statusCode == 403) {
                return "authorization";
            }
            return "service";
        }
        if (failure instanceof SdkClientException) {
            return "client";
        }
        if (failure instanceof IOException) {
            return "io";
        }
        return "other";
    }

    private static String exceptionType(Throwable failure) {
        var simpleName = failure.getClass().getSimpleName();
        return (simpleName.isEmpty() ? failure.getClass().getName() : simpleName)
            .toLowerCase(Locale.ROOT);
    }
}
