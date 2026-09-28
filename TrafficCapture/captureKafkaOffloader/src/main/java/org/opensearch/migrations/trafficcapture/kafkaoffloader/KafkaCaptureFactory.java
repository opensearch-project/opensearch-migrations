package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.opensearch.migrations.tracing.commoncontexts.IConnectionContext;
import org.opensearch.migrations.trafficcapture.CodedOutputStreamHolder;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.IConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.IConnectionCaptureReadiness;
import org.opensearch.migrations.trafficcapture.IOrderlyRetirableCaptureFactory;
import org.opensearch.migrations.trafficcapture.OrderedStreamLifecyleManager;
import org.opensearch.migrations.trafficcapture.StreamChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.tracing.IRootKafkaOffloaderContext;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.InvalidPartitionsException;
import org.apache.kafka.common.errors.RetriableException;

@Slf4j
public class KafkaCaptureFactory implements
    IConnectionCaptureFactory<RecordMetadata>,
    IConnectionCaptureReadiness,
    IOrderlyRetirableCaptureFactory,
    AutoCloseable {

    public static final String DEFAULT_TOPIC_NAME_FOR_TRAFFIC = "logging-traffic-topic";
    public static final Duration DEFAULT_TRAFFIC_STREAM_FLUSH_INTERVAL = Duration.ofSeconds(5);
    public static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(10);
    public static final Duration DEFAULT_HEARTBEAT_EXPIRATION_INTERVAL = Duration.ofSeconds(30);
    static final Duration DEFAULT_TOPIC_METADATA_DISCOVERY_RETRY_DELAY = Duration.ofSeconds(1);
    // This value encapsulates overhead we should reserve for a given Producer record to account for record key bytes
    // and
    // general Kafka message overhead
    public static final int KAFKA_MESSAGE_OVERHEAD_BYTES = 500;
    private final IRootKafkaOffloaderContext rootScope;
    private final String captureActivationId;
    private final String topicNameForTraffic;
    private final int bufferSize;
    private final Object initializationLock = new Object();
    private final CompletableFuture<CaptureKafkaPublisher> publisherFuture;
    private final Producer<String, byte[]> producer;
    private final Consumer<String, byte[]> membershipConsumer;
    private final Duration trafficStreamFlushInterval;
    private final Duration heartbeatInterval;
    private final Duration heartbeatExpirationInterval;
    private final Duration topicMetadataDiscoveryRetryDelay;
    private final int minimumTopicPartitionCount;
    private final TopicPartitionProvisioner topicPartitionProvisioner;
    private final java.util.function.Consumer<Throwable> captureFailureCallback;
    private final java.util.function.Consumer<Throwable> unstableProcessFailureCallback;
    private final ScheduledThreadPoolExecutor initializer;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean untransferredKafkaResourcesClosed = new AtomicBoolean();
    private final AtomicBoolean unstableFailureReported = new AtomicBoolean();
    private final AtomicBoolean topicPartitionProvisionerClosed = new AtomicBoolean();
    private volatile CaptureKafkaPublisher publisher;
    private volatile CaptureKafkaPublisher initializingPublisher;
    private volatile CaptureKafkaMembership membership;
    private final AtomicReference<CompletableFuture<Void>> orderlyRetirement = new AtomicReference<>();
    private volatile boolean producerLifecycleTransferredToPublisher;
    private final AtomicReference<CaptureKafkaWriteGate> writeGate = new AtomicReference<>();
    private final AtomicReference<Throwable> initializationFailure = new AtomicReference<>();
    private int topicMetadataDiscoveryFailures;
    private int topicPartitionProvisioningFailures;

    public KafkaCaptureFactory(
        IRootKafkaOffloaderContext rootScope,
        String captureActivationId,
        Producer<String, byte[]> producer,
        Consumer<String, byte[]> membershipConsumer,
        String topicNameForTraffic,
        int messageSize,
        Duration trafficStreamFlushInterval,
        Duration heartbeatInterval,
        Duration heartbeatExpirationInterval,
        java.util.function.Consumer<Throwable> captureFailureCallback,
        java.util.function.Consumer<Throwable> unstableProcessFailureCallback
    ) {
        this(
            rootScope,
            captureActivationId,
            producer,
            membershipConsumer,
            topicNameForTraffic,
            messageSize,
            trafficStreamFlushInterval,
            heartbeatInterval,
            heartbeatExpirationInterval,
            DEFAULT_TOPIC_METADATA_DISCOVERY_RETRY_DELAY,
            0,
            TopicPartitionProvisioner::disabled,
            captureFailureCallback,
            unstableProcessFailureCallback
        );
    }

    public KafkaCaptureFactory(
        IRootKafkaOffloaderContext rootScope,
        String captureActivationId,
        Producer<String, byte[]> producer,
        Consumer<String, byte[]> membershipConsumer,
        String topicNameForTraffic,
        int messageSize,
        Duration trafficStreamFlushInterval,
        Duration heartbeatInterval,
        Duration heartbeatExpirationInterval,
        Properties kafkaAdminProperties,
        int minimumTopicPartitionCount,
        java.util.function.Consumer<Throwable> captureFailureCallback,
        java.util.function.Consumer<Throwable> unstableProcessFailureCallback
    ) {
        this(
            rootScope,
            captureActivationId,
            producer,
            membershipConsumer,
            topicNameForTraffic,
            messageSize,
            trafficStreamFlushInterval,
            heartbeatInterval,
            heartbeatExpirationInterval,
            DEFAULT_TOPIC_METADATA_DISCOVERY_RETRY_DELAY,
            minimumTopicPartitionCount,
            () -> provisioner(kafkaAdminProperties, minimumTopicPartitionCount),
            captureFailureCallback,
            unstableProcessFailureCallback
        );
    }

    public KafkaCaptureFactory(
        IRootKafkaOffloaderContext rootScope,
        String captureActivationId,
        Producer<String, byte[]> producer,
        Consumer<String, byte[]> membershipConsumer,
        String topicNameForTraffic,
        int messageSize,
        java.util.function.Consumer<Throwable> captureFailureCallback,
        java.util.function.Consumer<Throwable> unstableProcessFailureCallback
    ) {
        this(
            rootScope,
            captureActivationId,
            producer,
            membershipConsumer,
            topicNameForTraffic,
            messageSize,
            DEFAULT_TRAFFIC_STREAM_FLUSH_INTERVAL,
            DEFAULT_HEARTBEAT_INTERVAL,
            DEFAULT_HEARTBEAT_EXPIRATION_INTERVAL,
            DEFAULT_TOPIC_METADATA_DISCOVERY_RETRY_DELAY,
            0,
            TopicPartitionProvisioner::disabled,
            captureFailureCallback,
            unstableProcessFailureCallback
        );
    }

    KafkaCaptureFactory(
        IRootKafkaOffloaderContext rootScope,
        String captureActivationId,
        Producer<String, byte[]> producer,
        Consumer<String, byte[]> membershipConsumer,
        String topicNameForTraffic,
        int messageSize,
        Duration trafficStreamFlushInterval,
        Duration heartbeatInterval,
        Duration heartbeatExpirationInterval,
        Duration topicMetadataDiscoveryRetryDelay,
        java.util.function.Consumer<Throwable> captureFailureCallback,
        java.util.function.Consumer<Throwable> unstableProcessFailureCallback
    ) {
        this(
            rootScope,
            captureActivationId,
            producer,
            membershipConsumer,
            topicNameForTraffic,
            messageSize,
            trafficStreamFlushInterval,
            heartbeatInterval,
            heartbeatExpirationInterval,
            topicMetadataDiscoveryRetryDelay,
            0,
            TopicPartitionProvisioner::disabled,
            captureFailureCallback,
            unstableProcessFailureCallback
        );
    }

    KafkaCaptureFactory(
        IRootKafkaOffloaderContext rootScope,
        String captureActivationId,
        Producer<String, byte[]> producer,
        Consumer<String, byte[]> membershipConsumer,
        String topicNameForTraffic,
        int messageSize,
        Duration trafficStreamFlushInterval,
        Duration heartbeatInterval,
        Duration heartbeatExpirationInterval,
        Duration topicMetadataDiscoveryRetryDelay,
        int minimumTopicPartitionCount,
        Supplier<TopicPartitionProvisioner> topicPartitionProvisionerSupplier,
        java.util.function.Consumer<Throwable> captureFailureCallback,
        java.util.function.Consumer<Throwable> unstableProcessFailureCallback
    ) {
        this.rootScope = Objects.requireNonNull(rootScope);
        this.captureActivationId = Objects.requireNonNull(captureActivationId);
        this.producer = Objects.requireNonNull(producer);
        this.membershipConsumer = Objects.requireNonNull(membershipConsumer);
        this.topicNameForTraffic = Objects.requireNonNull(topicNameForTraffic);
        this.trafficStreamFlushInterval = requirePositive(
            trafficStreamFlushInterval,
            "trafficStreamFlushInterval"
        );
        this.heartbeatInterval = requirePositive(heartbeatInterval, "heartbeatInterval");
        this.heartbeatExpirationInterval = requirePositive(
            heartbeatExpirationInterval,
            "heartbeatExpirationInterval"
        );
        if (heartbeatInterval.compareTo(heartbeatExpirationInterval) >= 0) {
            throw new IllegalArgumentException(
                "heartbeatInterval must be lower than heartbeatExpirationInterval"
            );
        }
        this.topicMetadataDiscoveryRetryDelay = requirePositive(
            topicMetadataDiscoveryRetryDelay,
            "topicMetadataDiscoveryRetryDelay"
        );
        if (minimumTopicPartitionCount < 0) {
            throw new IllegalArgumentException("minimumTopicPartitionCount must not be negative");
        }
        this.minimumTopicPartitionCount = minimumTopicPartitionCount;
        this.topicPartitionProvisioner = Objects.requireNonNull(
            Objects.requireNonNull(topicPartitionProvisionerSupplier).get()
        );
        this.captureFailureCallback = Objects.requireNonNull(captureFailureCallback);
        this.unstableProcessFailureCallback = Objects.requireNonNull(unstableProcessFailureCallback);
        this.bufferSize = checkedPayloadSize(messageSize);
        this.publisherFuture = new CompletableFuture<>();
        this.initializer = new ScheduledThreadPoolExecutor(1, runnable -> {
            var thread = new Thread(runnable, "capture-kafka-initializer");
            thread.setDaemon(true);
            return thread;
        });
        initializer.setRemoveOnCancelPolicy(true);
        initializer.execute(this::discoverTopicMetadata);
    }

    public CaptureKafkaPublisher getPublisher() {
        return publisherFuture.join();
    }

    CompletableFuture<CaptureKafkaPublisher> publisherReady() {
        return publisherFuture;
    }

    @Override
    public CompletableFuture<Void> readyForConnections() {
        return publisherFuture.thenApply(ignored -> null);
    }

    @Override
    public IChannelConnectionCaptureSerializer<RecordMetadata> createOffloader(IConnectionContext ctx) {
        var connectionId = Objects.requireNonNull(
            ctx.getConnectionId(),
            "connectionId must not be null - partition locality requires a stable key"
        );
        CaptureKafkaPublisher readyPublisher;
        CaptureRoutingState.ConnectionRoute route = null;
        IllegalStateException unavailableBeforeAssignment = null;
        synchronized (initializationLock) {
            if (closed.get()) {
                throw new IllegalStateException("Kafka capture factory is closed");
            }
            if (orderlyRetirement.get() != null) {
                throw new IllegalStateException("Kafka capture factory is retiring for orderly shutdown");
            }
            var terminalFailure = initializationFailure.get();
            if (terminalFailure != null) {
                throw new IllegalStateException("Kafka capture is permanently unavailable", terminalFailure);
            }
            readyPublisher = publisher;
            if (readyPublisher == null) {
                unavailableBeforeAssignment = new IllegalStateException(
                    "Kafka capture is not accepting new connections before its first group assignment"
                );
            } else {
                try {
                    route = readyPublisher.getRoutingState().routeNewConnection(connectionId);
                } catch (CorruptedCaptureStateException e) {
                    failUnstable(e);
                    throw e;
                }
            }
        }
        if (unavailableBeforeAssignment != null) {
            failCapture(unavailableBeforeAssignment);
            throw unavailableBeforeAssignment;
        }
        return createRoutedOffloader(ctx, Objects.requireNonNull(route), readyPublisher);
    }

    private IChannelConnectionCaptureSerializer<RecordMetadata> createRoutedOffloader(
        IConnectionContext ctx,
        CaptureRoutingState.ConnectionRoute route,
        CaptureKafkaPublisher readyPublisher
    ) {
        try {
            return new StreamChannelConnectionCaptureSerializer<>(
                route.writerNodeId(),
                route.connectionId(),
                new StreamManager(ctx, route),
                trafficStreamFlushInterval,
                acknowledgement ->
                    readyPublisher.validateCriticalMutationTrafficAcknowledgement(route, acknowledgement)
            );
        } catch (Error t) {
            readyPublisher.abandonUnpublishedConnection(route);
            failUnstable(t);
            throw t;
        } catch (RuntimeException t) {
            readyPublisher.abandonUnpublishedConnection(route);
            throw t;
        }
    }

    private void discoverTopicMetadata() {
        if (closed.get() || orderlyRetirement.get() != null) {
            return;
        }
        try {
            var topicMetadata = TrafficTopicMetadata.discover(producer, topicNameForTraffic);
            if (topicMetadata.getTopicPartitionCount() < minimumTopicPartitionCount) {
                increaseTopicPartitionCount(topicMetadata);
            } else {
                if (minimumTopicPartitionCount > 0) {
                    logConfiguredTopicPartitionMinimum(topicMetadata);
                }
                publishStartupCapabilityProbes(topicMetadata);
            }
        } catch (IllegalArgumentException e) {
            failCapture(e);
        } catch (RuntimeException e) {
            retryTopicMetadataDiscovery(e, this::discoverTopicMetadata);
        } catch (Error e) {
            failUnstable(e);
        }
    }

    @SuppressWarnings("java:S1181") // Kafka ownership errors must reach the process-fatal policy.
    private void increaseTopicPartitionCount(TrafficTopicMetadata topicMetadata) {
        log.atInfo()
            .setMessage(
                "Kafka traffic topic {} has {} partition(s); "
                    + "increasing to configured minimum {} before proxy-group membership"
            )
            .addArgument(topicNameForTraffic)
            .addArgument(topicMetadata.getTopicPartitionCount())
            .addArgument(minimumTopicPartitionCount)
            .log();
        final CompletableFuture<Void> increase;
        try {
            increase = topicPartitionProvisioner.increaseTo(
                topicNameForTraffic,
                minimumTopicPartitionCount
            );
        } catch (RuntimeException e) {
            handleTopicPartitionProvisioningFailure(e);
            return;
        } catch (Error e) {
            failUnstable(e);
            return;
        }
        increase.whenComplete((ignored, failure) -> {
            if (closed.get() || orderlyRetirement.get() != null) {
                return;
            }
            if (failure != null) {
                handleTopicPartitionProvisioningFailure(unwrapCompletionFailure(failure));
                return;
            }
            discoverProvisionedTopicMetadataAndProbe();
        });
    }

    private void handleTopicPartitionProvisioningFailure(Throwable failure) {
        if (failure instanceof InvalidPartitionsException) {
            log.atInfo()
                .setCause(failure)
                .setMessage(
                    "Kafka traffic-topic partition increase raced with another proxy; "
                        + "refreshing metadata before continuing startup"
                )
                .log();
            discoverProvisionedTopicMetadataAndProbe();
        } else if (failure instanceof RetriableException retriableFailure) {
            retryTopicPartitionProvisioning(retriableFailure);
        } else {
            handleKafkaFailure(failure);
        }
    }

    private void retryTopicPartitionProvisioning(RuntimeException failure) {
        if (closed.get() || orderlyRetirement.get() != null) {
            return;
        }
        topicPartitionProvisioningFailures++;
        if (topicPartitionProvisioningFailures == 1) {
            log.atWarn()
                .setCause(failure)
                .setMessage("Kafka traffic-topic partition increase is unavailable; capture initialization will retry")
                .log();
        } else {
            log.atDebug()
                .setCause(failure)
                .setMessage("Kafka traffic-topic partition increase remains unavailable; retry={}")
                .addArgument(topicPartitionProvisioningFailures)
                .log();
        }
        scheduleInitializerAction(this::discoverTopicMetadata);
    }

    @SuppressWarnings("java:S1181") // Kafka ownership errors must reach the process-fatal policy.
    private void discoverProvisionedTopicMetadataAndProbe() {
        final CompletableFuture<TrafficTopicMetadata> discovery;
        try {
            discovery = topicPartitionProvisioner.discoverMetadata(topicNameForTraffic);
        } catch (RuntimeException e) {
            retryTopicMetadataDiscovery(e, this::discoverProvisionedTopicMetadataAndProbe);
            return;
        } catch (Error e) {
            failUnstable(e);
            return;
        }
        discovery.whenComplete((topicMetadata, failure) ->
            executeInitializerAction(
                () -> finishProvisionedTopicMetadataDiscovery(topicMetadata, failure)
            )
        );
    }

    private void finishProvisionedTopicMetadataDiscovery(
        TrafficTopicMetadata topicMetadata,
        Throwable failure
    ) {
        if (failure != null) {
            handleTopicMetadataDiscoveryFailure(
                unwrapCompletionFailure(failure),
                this::discoverProvisionedTopicMetadataAndProbe
            );
            return;
        }
        try {
            var discoveredMetadata = Objects.requireNonNull(
                topicMetadata,
                "Kafka Admin returned no traffic-topic metadata"
            );
            if (discoveredMetadata.getTopicPartitionCount() < minimumTopicPartitionCount) {
                retryTopicMetadataDiscovery(
                    new IllegalStateException(
                        "Kafka traffic topic "
                            + topicNameForTraffic
                            + " still reports "
                            + discoveredMetadata.getTopicPartitionCount()
                            + " partition(s) after increasing to "
                            + minimumTopicPartitionCount
                    ),
                    this::discoverProvisionedTopicMetadataAndProbe
                );
                return;
            }
            logConfiguredTopicPartitionMinimum(discoveredMetadata);
            publishStartupCapabilityProbes(discoveredMetadata);
        } catch (RuntimeException e) {
            retryTopicMetadataDiscovery(e, this::discoverProvisionedTopicMetadataAndProbe);
        } catch (Error e) {
            failUnstable(e);
        }
    }

    private void scheduleInitializerAction(Runnable action) {
        if (closed.get() || orderlyRetirement.get() != null || initializer.isShutdown()) {
            return;
        }
        try {
            initializer.schedule(
                action,
                topicMetadataDiscoveryRetryDelay.toMillis(),
                TimeUnit.MILLISECONDS
            );
        } catch (RejectedExecutionException e) {
            if (!closed.get()
                && orderlyRetirement.get() == null
                && initializationFailure.get() == null) {
                failUnstable(e);
            }
        }
    }

    private void executeInitializerAction(Runnable action) {
        if (closed.get() || orderlyRetirement.get() != null || initializer.isShutdown()) {
            return;
        }
        try {
            initializer.execute(action);
        } catch (RejectedExecutionException e) {
            if (!closed.get()
                && orderlyRetirement.get() == null
                && initializationFailure.get() == null) {
                failUnstable(e);
            }
        }
    }

    private void logConfiguredTopicPartitionMinimum(TrafficTopicMetadata topicMetadata) {
        log.atInfo()
            .setMessage(
                "Kafka traffic topic {} satisfies the configured partition minimum; "
                    + "actual={}, minimum={}"
            )
            .addArgument(topicNameForTraffic)
            .addArgument(topicMetadata.getTopicPartitionCount())
            .addArgument(minimumTopicPartitionCount)
            .log();
    }

    private void publishStartupCapabilityProbes(TrafficTopicMetadata topicMetadata) {
        CaptureKafkaCapabilityProbe.publish(
            producer,
            topicNameForTraffic,
            captureActivationId,
            topicMetadata.getRepresentativePartitionsByLeader()
        ).whenComplete((ignored, failure) -> {
            if (closed.get() || orderlyRetirement.get() != null) {
                return;
            }
            if (failure != null) {
                handleKafkaFailure(unwrapCompletionFailure(failure));
                return;
            }
            try {
                initializer.execute(() -> refreshTopicMetadataAfterProbe(topicMetadata));
            } catch (RejectedExecutionException e) {
                if (!closed.get()
                    && orderlyRetirement.get() == null
                    && initializationFailure.get() == null) {
                    failUnstable(e);
                }
            }
        });
    }

    private void refreshTopicMetadataAfterProbe(TrafficTopicMetadata probedMetadata) {
        if (closed.get() || orderlyRetirement.get() != null) {
            return;
        }
        if (minimumTopicPartitionCount > 0) {
            refreshProvisionedTopicMetadataAfterProbe(probedMetadata);
            return;
        }
        try {
            var refreshedMetadata = TrafficTopicMetadata.discover(producer, topicNameForTraffic);
            finishTopicMetadataRefreshAfterProbe(probedMetadata, refreshedMetadata);
        } catch (IllegalArgumentException e) {
            failCapture(e);
        } catch (RuntimeException e) {
            retryTopicMetadataDiscovery(e, () -> refreshTopicMetadataAfterProbe(probedMetadata));
        } catch (Error e) {
            failUnstable(e);
        }
    }

    @SuppressWarnings("java:S1181") // Kafka ownership errors must reach the process-fatal policy.
    private void refreshProvisionedTopicMetadataAfterProbe(
        TrafficTopicMetadata probedMetadata
    ) {
        final CompletableFuture<TrafficTopicMetadata> discovery;
        try {
            discovery = topicPartitionProvisioner.discoverMetadata(topicNameForTraffic);
        } catch (RuntimeException e) {
            retryTopicMetadataDiscovery(
                e,
                () -> refreshTopicMetadataAfterProbe(probedMetadata)
            );
            return;
        } catch (Error e) {
            failUnstable(e);
            return;
        }
        discovery.whenComplete((refreshedMetadata, failure) ->
            executeInitializerAction(
                () -> finishProvisionedTopicMetadataRefresh(
                    probedMetadata,
                    refreshedMetadata,
                    failure
                )
            )
        );
    }

    private void finishProvisionedTopicMetadataRefresh(
        TrafficTopicMetadata probedMetadata,
        TrafficTopicMetadata refreshedMetadata,
        Throwable failure
    ) {
        if (failure != null) {
            handleTopicMetadataDiscoveryFailure(
                unwrapCompletionFailure(failure),
                () -> refreshTopicMetadataAfterProbe(probedMetadata)
            );
            return;
        }
        try {
            var discoveredMetadata = Objects.requireNonNull(
                refreshedMetadata,
                "Kafka Admin returned no traffic-topic metadata after startup probing"
            );
            if (discoveredMetadata.getTopicPartitionCount() < minimumTopicPartitionCount) {
                retryTopicMetadataDiscovery(
                    new IllegalStateException(
                        "Kafka traffic topic "
                            + topicNameForTraffic
                            + " reports fewer than the configured minimum "
                            + minimumTopicPartitionCount
                            + " partition(s) after startup probing"
                    ),
                    () -> refreshTopicMetadataAfterProbe(probedMetadata)
                );
                return;
            }
            finishTopicMetadataRefreshAfterProbe(probedMetadata, discoveredMetadata);
        } catch (RuntimeException e) {
            retryTopicMetadataDiscovery(
                e,
                () -> refreshTopicMetadataAfterProbe(probedMetadata)
            );
        } catch (Error e) {
            failUnstable(e);
        }
    }

    private void handleTopicMetadataDiscoveryFailure(
        Throwable failure,
        Runnable retryAction
    ) {
        if (failure instanceof Error) {
            failUnstable(failure);
        } else if (failure instanceof RuntimeException runtimeFailure) {
            retryTopicMetadataDiscovery(runtimeFailure, retryAction);
        } else {
            failCapture(new IllegalStateException("Kafka topic metadata discovery failed", failure));
        }
    }

    private void finishTopicMetadataRefreshAfterProbe(
        TrafficTopicMetadata probedMetadata,
        TrafficTopicMetadata refreshedMetadata
    ) {
        if (probedMetadata.getLeaderIds().containsAll(refreshedMetadata.getLeaderIds())) {
            closeTopicPartitionProvisioner();
            startMembershipInitialization(refreshedMetadata);
        } else {
            log.atInfo()
                .setMessage(
                    "Kafka traffic-topic leadership changed during startup probing; "
                        + "probing the newly current leaders before joining the capture group"
                )
                .log();
            publishStartupCapabilityProbes(refreshedMetadata);
        }
    }

    private void startMembershipInitialization(TrafficTopicMetadata topicMetadata) {
        CaptureKafkaMembership initializedMembership;
        synchronized (initializationLock) {
            if (closed.get()
                || orderlyRetirement.get() != null
                || initializationFailure.get() != null) {
                return;
            }
            var routingState = new CaptureRoutingState(
                captureActivationId,
                topicMetadata.getTopicPartitionCount()
            );
            var createdWriteGate = new CaptureKafkaWriteGate();
            var createdPublisher = new CaptureKafkaPublisher(
                producer,
                topicNameForTraffic,
                routingState,
                bufferSize + KAFKA_MESSAGE_OVERHEAD_BYTES,
                heartbeatInterval,
                heartbeatExpirationInterval,
                java.time.Clock.systemUTC(),
                createdWriteGate,
                this::failUnstable
            );
            writeGate.set(createdWriteGate);
            initializingPublisher = createdPublisher;
            producerLifecycleTransferredToPublisher = true;
            createdWriteGate.addTerminalFailureListener(this::handleKafkaFailure);
            initializedMembership = new CaptureKafkaMembership(
                membershipConsumer,
                topicNameForTraffic,
                routingState,
                createdPublisher,
                this::finishMembershipInitialization,
                this::failCapture,
                this::failUnstable
            );
            membership = initializedMembership;
        }
        initializer.shutdown();
        initializedMembership.start();
        log.atInfo()
            .setMessage("Kafka capture metadata is ready; waiting for the first proxy-group assignment")
            .log();
    }

    private void finishMembershipInitialization() {
        CaptureKafkaPublisher initializedPublisher;
        try {
            var gateFailure = Objects.requireNonNull(writeGate.get()).failureIfNotWritable();
            if (gateFailure != null) {
                failCapture(gateFailure);
                return;
            }
            synchronized (initializationLock) {
                if (closed.get()
                    || orderlyRetirement.get() != null
                    || initializationFailure.get() != null
                    || publisher != null) {
                    return;
                }
                initializedPublisher = Objects.requireNonNull(initializingPublisher);
                publisher = initializedPublisher;
                initializingPublisher = null;
            }
            publisherFuture.complete(initializedPublisher);
            log.atInfo()
                .setMessage("Initialized Kafka capture from proxy-group assignment {}")
                .addArgument(initializedPublisher.getRoutingState().assignedPartitions())
                .log();
        } catch (RuntimeException e) {
            failCapture(e);
        } catch (Error e) {
            failUnstable(e);
        }
    }

    private void retryTopicMetadataDiscovery(RuntimeException failure, Runnable retryAction) {
        if (closed.get() || orderlyRetirement.get() != null) {
            return;
        }
        topicMetadataDiscoveryFailures++;
        if (topicMetadataDiscoveryFailures == 1) {
            log.atWarn()
                .setCause(failure)
                .setMessage("Kafka topic metadata is unavailable; capture initialization will retry")
                .log();
        } else {
            log.atDebug()
                .setCause(failure)
                .setMessage("Kafka topic metadata remains unavailable; retry={}")
                .addArgument(topicMetadataDiscoveryFailures)
                .log();
        }
        if (!initializer.isShutdown()) {
            try {
                initializer.schedule(
                    retryAction,
                    topicMetadataDiscoveryRetryDelay.toMillis(),
                    TimeUnit.MILLISECONDS
                );
            } catch (RejectedExecutionException e) {
                if (!closed.get()
                    && orderlyRetirement.get() == null
                    && initializationFailure.get() == null) {
                    failUnstable(e);
                }
            }
        }
    }

    /**
     * Stops assignment changes and waits until all captured connections and assignment-scoped
     * writers have completed the orderly retirement protocol. The caller remains responsible for
     * process-level warning and termination deadlines and for subsequently calling {@link #close()}.
     */
    @Override
    public CompletableFuture<Void> retireForOrderlyShutdown() {
        final CompletableFuture<Void> result;
        final CaptureKafkaMembership membershipToClose;
        final CaptureKafkaPublisher publisherToRetire;
        synchronized (initializationLock) {
            var existingRetirement = orderlyRetirement.get();
            if (existingRetirement != null) {
                return existingRetirement;
            }
            if (closed.get()) {
                return CompletableFuture.failedFuture(
                    new IllegalStateException("Kafka capture factory is already closed")
                );
            }
            var terminalFailure = initializationFailure.get();
            if (terminalFailure != null) {
                return CompletableFuture.failedFuture(terminalFailure);
            }
            result = new CompletableFuture<>();
            orderlyRetirement.set(result);
            membershipToClose = membership;
            publisherToRetire = publisher == null ? initializingPublisher : publisher;
        }

        initializer.shutdownNow();
        var membershipStopped = membershipToClose == null
            ? CompletableFuture.<Void>completedFuture(null)
            : membershipToClose.closeAsync();
        var membershipCallbacksStopped = membershipStopped.handle((ignored, failure) -> {
            if (failure != null) {
                log.atWarn()
                    .setCause(unwrapCompletionFailure(failure))
                    .setMessage(
                        "Kafka membership did not close cleanly during orderly shutdown; "
                            + "continuing capture retirement after membership callbacks stopped"
                    )
                    .log();
            }
            return null;
        });

        if (publisherToRetire == null) {
            membershipCallbacksStopped.whenComplete((ignored, failure) -> result.complete(null));
            return result;
        }
        CompletableFuture.allOf(
            membershipCallbacksStopped,
            publisherToRetire.getRoutingState().whenNoConnections()
        )
            .thenCompose(ignored -> publisherToRetire.retireAllWriters())
            .whenComplete((ignored, failure) -> {
                if (failure == null) {
                    result.complete(null);
                } else {
                    result.completeExceptionally(unwrapCompletionFailure(failure));
                }
            });
        return result;
    }

    private static Throwable unwrapCompletionFailure(Throwable failure) {
        if (failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }

    private void handleKafkaFailure(Throwable failure) {
        if (failure instanceof Error) {
            failUnstable(failure);
        } else {
            failCapture(failure);
        }
    }

    private void failCapture(Throwable failure) {
        failAndCloseCapture(failure, true);
    }

    private void failUnstable(Throwable failure) {
        if (unstableFailureReported.compareAndSet(false, true)) {
            log.atError()
                .setCause(failure)
                .setMessage("Kafka capture process ownership is unstable; terminating the process")
                .log();
            unstableProcessFailureCallback.accept(failure);
        }
        failAndCloseCapture(failure, false);
    }

    private void failAndCloseCapture(Throwable failure, boolean notifyCaptureFailurePolicy) {
        CaptureKafkaPublisher publisherToFail;
        CaptureKafkaMembership membershipToClose;
        synchronized (initializationLock) {
            if (initializationFailure.get() != null) {
                return;
            }
            initializationFailure.set(failure);
            publisherToFail = publisher == null ? initializingPublisher : publisher;
            initializingPublisher = null;
            membershipToClose = membership;
        }
        if (publisherToFail != null) {
            publisherToFail.stopAfterFailure(failure);
        }
        publisherFuture.completeExceptionally(failure);
        if (initializer != null) {
            initializer.shutdownNow();
        }
        log.atError()
            .setCause(failure)
            .setMessage("Kafka capture is permanently unavailable in this process")
            .log();
        if (notifyCaptureFailurePolicy) {
            captureFailureCallback.accept(failure);
        }
        if (publisherToFail != null || membershipToClose != null) {
            var closeThread = new Thread(
                () -> closeFailedCaptureResources(membershipToClose, publisherToFail),
                "failed-capture-kafka-publisher-close"
            );
            closeThread.setDaemon(true);
            closeThread.start();
        } else {
            var closeThread = new Thread(
                this::closeUntransferredKafkaResources,
                "failed-capture-kafka-resource-close"
            );
            closeThread.setDaemon(true);
            closeThread.start();
        }
    }

    private static void closeFailedCaptureResources(
        CaptureKafkaMembership membership,
        CaptureKafkaPublisher publisher
    ) {
        if (membership != null) {
            membership.close();
        }
        if (publisher != null) {
            publisher.close();
        }
    }

    private static int checkedPayloadSize(int messageSize) {
        if (messageSize <= KAFKA_MESSAGE_OVERHEAD_BYTES) {
            throw new IllegalArgumentException("messageSize is too small for Kafka record overhead");
        }
        return messageSize - KAFKA_MESSAGE_OVERHEAD_BYTES;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static TopicPartitionProvisioner provisioner(
        Properties kafkaAdminProperties,
        int minimumTopicPartitionCount
    ) {
        if (minimumTopicPartitionCount == 0) {
            return TopicPartitionProvisioner.disabled();
        }
        return new KafkaAdminTopicPartitionProvisioner(
            Admin.create(Objects.requireNonNull(kafkaAdminProperties))
        );
    }

    private void closeTopicPartitionProvisioner() {
        if (!topicPartitionProvisionerClosed.compareAndSet(false, true)) {
            return;
        }
        try {
            topicPartitionProvisioner.close();
        } catch (RuntimeException e) {
            log.atWarn()
                .setCause(e)
                .setMessage("Kafka traffic-topic partition provisioner did not close cleanly")
                .log();
        }
    }

    interface TopicPartitionProvisioner extends AutoCloseable {
        CompletableFuture<Void> increaseTo(String topic, int partitionCount);

        CompletableFuture<TrafficTopicMetadata> discoverMetadata(String topic);

        @Override
        void close();

        static TopicPartitionProvisioner disabled() {
            return DisabledTopicPartitionProvisioner.INSTANCE;
        }
    }

    private enum DisabledTopicPartitionProvisioner implements TopicPartitionProvisioner {
        INSTANCE;

        @Override
        public CompletableFuture<Void> increaseTo(String topic, int partitionCount) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<TrafficTopicMetadata> discoverMetadata(String topic) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Kafka topic partition provisioning is disabled")
            );
        }

        @Override
        public void close() {
            // The disabled singleton owns no Kafka Admin client or other closeable resource.
        }
    }

    private static final class KafkaAdminTopicPartitionProvisioner
        implements TopicPartitionProvisioner {

        private final Admin admin;
        private final AtomicBoolean closed = new AtomicBoolean();

        private KafkaAdminTopicPartitionProvisioner(Admin admin) {
            this.admin = Objects.requireNonNull(admin);
        }

        @Override
        public CompletableFuture<Void> increaseTo(String topic, int partitionCount) {
            Objects.requireNonNull(topic);
            if (partitionCount <= 0) {
                throw new IllegalArgumentException("partitionCount must be positive");
            }
            if (closed.get()) {
                return CompletableFuture.failedFuture(
                    new IllegalStateException("Kafka topic partition provisioner is closed")
                );
            }
            var result = new CompletableFuture<Void>();
            try {
                admin.createPartitions(
                    Map.of(topic, NewPartitions.increaseTo(partitionCount))
                ).all().whenComplete((ignored, failure) -> {
                    if (failure == null) {
                        result.complete(null);
                    } else {
                        result.completeExceptionally(failure);
                    }
                });
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
            return result;
        }

        @Override
        public CompletableFuture<TrafficTopicMetadata> discoverMetadata(String topic) {
            Objects.requireNonNull(topic);
            if (closed.get()) {
                return CompletableFuture.failedFuture(
                    new IllegalStateException("Kafka topic partition provisioner is closed")
                );
            }
            var result = new CompletableFuture<TrafficTopicMetadata>();
            try {
                admin.describeTopics(List.of(topic))
                    .allTopicNames()
                    .whenComplete((descriptions, failure) -> {
                        if (failure != null) {
                            result.completeExceptionally(failure);
                            return;
                        }
                        try {
                            result.complete(
                                TrafficTopicMetadata.fromTopicDescription(
                                    topic,
                                    descriptions.get(topic)
                                )
                            );
                        } catch (Throwable t) {
                            result.completeExceptionally(t);
                        }
                    });
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
            return result;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                admin.close(Duration.ZERO);
            }
        }
    }

    @AllArgsConstructor
    static class CodedOutputStreamWrapper implements CodedOutputStreamHolder {
        private final CodedOutputStream codedOutputStream;
        private final ByteBuffer byteBuffer;

        @Override
        public int getOutputStreamBytesLimit() {
            return byteBuffer.limit();
        }

        @Override
        public @NonNull CodedOutputStream getOutputStream() {
            return codedOutputStream;
        }
    }

    class StreamManager extends OrderedStreamLifecyleManager<RecordMetadata> {
        IConnectionContext telemetryContext;
        CaptureRoutingState.ConnectionRoute route;

        public StreamManager(
            IConnectionContext ctx,
            CaptureRoutingState.ConnectionRoute route
        ) {
            // TODO - add https://opentelemetry.io/blog/2022/instrument-kafka-clients/
            this.telemetryContext = ctx;
            this.route = route;
        }

        private byte[] payload(CodedOutputStreamHolder outputStreamHolder) {
            if (!(outputStreamHolder instanceof CodedOutputStreamWrapper outputStream)) {
                throw new IllegalArgumentException(
                    "Unknown outputStreamHolder sent back to StreamManager: " + outputStreamHolder
                );
            }
            return Arrays.copyOfRange(
                outputStream.byteBuffer.array(),
                0,
                outputStream.byteBuffer.position()
            );
        }

        private boolean isTerminalRecord(TrafficStream trafficStream) {
            var finalChunk = trafficStream.hasNumberOfThisLastChunk();
            var closeCount = trafficStream.getSubStreamList()
                .stream()
                .filter(observation -> observation.hasClose())
                .count();
            var closeIsLast = closeCount == 1
                && trafficStream.getSubStream(trafficStream.getSubStreamCount() - 1).hasClose();
            if (finalChunk != closeIsLast) {
                var terminalRecordFailure = new IllegalStateException(
                    "A connection's final Kafka record must contain exactly one terminal CloseObservation as its last observation"
                );
                failUnstable(terminalRecordFailure);
                throw terminalRecordFailure;
            }
            return finalChunk;
        }

        private CompletableFuture<RecordMetadata> publishPayload(
            TrafficStream trafficStream,
            boolean finalRecord,
            int index,
            CaptureKafkaPublisher readyPublisher
        ) {
            String recordId = String.format("%s.%d", route.connectionId(), index);
            var flushContext = rootScope.createKafkaRecordContext(
                telemetryContext,
                topicNameForTraffic,
                recordId,
                trafficStream.getSerializedSize()
            );
            return readyPublisher.publishTraffic(route, trafficStream, finalRecord)
                .whenComplete((recordMetadata, throwable) -> {
                    if (throwable != null) {
                        flushContext.addTraceException(throwable, true);
                        log.error("Error sending producer record: {}", recordId, throwable);
                    } else {
                        log.debug(
                            "Kafka producer record: {} has finished sending for topic: {} and partition {}",
                            recordId,
                            recordMetadata.topic(),
                            recordMetadata.partition()
                        );
                    }
                    flushContext.close();
                });
        }

        @Override
        public CodedOutputStreamWrapper createStream() {
            telemetryContext.addEvent("streamCreated");

            ByteBuffer bb = ByteBuffer.allocate(bufferSize);
            return new CodedOutputStreamWrapper(CodedOutputStream.newInstance(bb), bb);
        }

        @Override
        public CompletableFuture<RecordMetadata> kickoffCloseStream(
            CodedOutputStreamHolder outputStreamHolder,
            int index
        ) {
            try {
                var recordPayload = payload(outputStreamHolder);
                var trafficStream = TrafficStream.parseFrom(recordPayload);
                return publishPayload(
                    trafficStream,
                    isTerminalRecord(trafficStream),
                    index,
                    Objects.requireNonNull(publisher)
                );
            } catch (InvalidProtocolBufferException e) {
                return CompletableFuture.failedFuture(e);
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (initializer != null) {
            initializer.shutdownNow();
        }
        // Routing initialization decides whether to build a publisher while holding this lock and after
        // checking the flag set above, so close has to take the lock to read the result of that decision.
        // Reading it unlocked can observe no publisher while one is being constructed, which would leave
        // it running -- with its heartbeat timers -- against the producer closed just below.
        CaptureKafkaPublisher readyPublisher;
        CaptureKafkaPublisher publisherStillInitializing;
        CaptureKafkaMembership readyMembership;
        synchronized (initializationLock) {
            readyPublisher = publisher;
            publisherStillInitializing = initializingPublisher;
            readyMembership = membership;
        }
        if (readyPublisher != null) {
            if (readyMembership != null) {
                readyMembership.close();
            }
            readyPublisher.close();
            return;
        }
        if (readyMembership != null) {
            readyMembership.close();
        }
        if (publisherStillInitializing != null) {
            publisherStillInitializing.close();
            return;
        }
        publisherFuture.completeExceptionally(new IllegalStateException("Kafka capture factory closed before initialization"));
        if (!producerLifecycleTransferredToPublisher) {
            closeUntransferredKafkaResources();
        }
    }

    private void closeUntransferredKafkaResources() {
        if (!untransferredKafkaResourcesClosed.compareAndSet(false, true)) {
            return;
        }
        try {
            membershipConsumer.close(Duration.ZERO);
        } finally {
            try {
                producer.close(Duration.ZERO);
            } finally {
                closeTopicPartitionProvisioner();
            }
        }
    }
}
