package org.opensearch.migrations.trafficcapture.proxyserver.testcontainers;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.opensearch.migrations.testutils.SharedDockerImageNames;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.KafkaCaptureFactory;

import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.TopicExistsException;
import org.testcontainers.kafka.ConfluentKafkaContainer;


public class KafkaContainerTestBase extends TestContainerTestBase<ConfluentKafkaContainer> {

    private static final ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(SharedDockerImageNames.KAFKA);

    @Override
    public void start() {
        super.start();
        createTrafficTopic(KafkaCaptureFactory.DEFAULT_TOPIC_NAME_FOR_TRAFFIC);
    }

    public ConfluentKafkaContainer getContainer() {
        return kafka;
    }

    /** Creates a traffic topic the capture proxy's startup capability probe will accept. */
    public void createTrafficTopic(String topic) {
        try {
            withAdmin(admin -> admin.createTopics(List.of(
                new NewTopic(topic, 1, (short) 1)
                    .configs(Map.of(TopicConfig.MESSAGE_TIMESTAMP_TYPE_CONFIG, "LogAppendTime"))
            )).all(), "create the proxy traffic topic " + topic);
        } catch (IllegalStateException e) {
            if (!causedBy(e, TopicExistsException.class)) {
                throw e;
            }
            var resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
            var configs = withAdmin(
                admin -> admin.describeConfigs(Set.of(resource)).all(),
                "read the existing proxy traffic topic " + topic
            );
            var timestampType = configs.get(resource).get(TopicConfig.MESSAGE_TIMESTAMP_TYPE_CONFIG).value();
            if (!"LogAppendTime".equals(timestampType)) {
                throw new IllegalStateException(
                    "Existing proxy traffic topic " + topic + " uses message.timestamp.type=" + timestampType
                );
            }
        }
    }

    /**
     * Makes every subsequent produce to the caller-owned topic fail with a non-retriable
     * record-too-large error.
     */
    public void rejectAllProducesToTopic(String topic) {
        withAdmin(admin -> admin.incrementalAlterConfigs(Map.of(
            new ConfigResource(ConfigResource.Type.TOPIC, topic),
            List.of(new AlterConfigOp(
                new ConfigEntry(TopicConfig.MAX_MESSAGE_BYTES_CONFIG, "1"),
                AlterConfigOp.OpType.SET
            ))
        )).all(), "reject produces to " + topic);
    }

    public void deleteTopic(String topic) {
        withAdmin(admin -> admin.deleteTopics(List.of(topic)).all(), "delete " + topic);
    }

    private <T> T withAdmin(Function<AdminClient, KafkaFuture<T>> action, String description) {
        var properties = new Properties();
        properties.setProperty(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
            stripScheme(kafka.getBootstrapServers())
        );
        try (var admin = AdminClient.create(properties)) {
            return action.apply(admin).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to " + description, e);
        }
    }

    private static boolean causedBy(Throwable failure, Class<? extends Throwable> causeType) {
        for (var current = failure; current != null; current = current.getCause()) {
            if (causeType.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    private static String stripScheme(String bootstrapServers) {
        var schemeEnd = bootstrapServers.indexOf("://");
        return schemeEnd < 0 ? bootstrapServers : bootstrapServers.substring(schemeEnd + 3);
    }
}
