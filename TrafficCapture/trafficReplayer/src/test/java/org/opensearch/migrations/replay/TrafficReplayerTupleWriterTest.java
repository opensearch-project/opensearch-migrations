package org.opensearch.migrations.replay;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Supplier;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.transform.IJsonTransformer;

/**
 * Covers {@code TrafficReplayer.createS3TupleWriterIfConfigured} and the helpers it was split
 * into ({@code buildS3SinkFactory}, {@code buildKafkaTupleProducer}, {@code combineTupleSinkFactories}),
 * none of which had prior dedicated test coverage.
 */
public class TrafficReplayerTupleWriterTest {

    private static final Supplier<IJsonTransformer> NO_OP_TRANSFORMER_SUPPLIER = () -> incomingJson -> incomingJson;

    private static Object invokeCreateS3TupleWriterIfConfigured(TrafficReplayer.Parameters params) throws Exception {
        Method method = TrafficReplayer.class.getDeclaredMethod(
            "createS3TupleWriterIfConfigured",
            TrafficReplayer.Parameters.class,
            Supplier.class
        );
        method.setAccessible(true);
        try {
            return method.invoke(null, params, NO_OP_TRANSFORMER_SUPPLIER);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException) {
                throw (RuntimeException) e.getCause();
            }
            throw e;
        }
    }

    @Test
    public void testNeitherS3NorKafkaConfiguredReturnsNull() throws Exception {
        var params = new TrafficReplayer.Parameters();

        var result = invokeCreateS3TupleWriterIfConfigured(params);

        Assertions.assertNull(result);
    }

    @Test
    public void testKafkaOnlyWithUnreadablePropertyFileThrowsConfigurationException() {
        var params = new TrafficReplayer.Parameters();
        params.tupleKafkaTopic = "tuple-output";
        params.kafkaTrafficBrokers = "broker:9092";
        params.kafkaTrafficPropertyFile = "/nonexistent/path/client.properties";

        var thrown = Assertions.assertThrows(
            TupleWriterConfigurationException.class,
            () -> invokeCreateS3TupleWriterIfConfigured(params)
        );
        Assertions.assertNotNull(thrown.getCause());
    }

    @Test
    public void testS3OnlyConfiguredReturnsCloseableResources() throws Exception {
        var params = new TrafficReplayer.Parameters();
        params.tupleS3Bucket = "test-bucket";
        params.tupleS3Region = "us-east-2";
        params.tupleS3Endpoint = "http://localhost:4566";

        var result = invokeCreateS3TupleWriterIfConfigured(params);

        Assertions.assertNotNull(result);
        Assertions.assertInstanceOf(AutoCloseable.class, result);
        ((AutoCloseable) result).close();
    }

    @Test
    public void testS3AndKafkaConfiguredReturnsCloseableResources() throws Exception {
        var params = new TrafficReplayer.Parameters();
        params.tupleS3Bucket = "test-bucket";
        params.tupleS3Region = "us-east-2";
        params.tupleS3Endpoint = "http://localhost:4566";
        params.tupleKafkaTopic = "tuple-output";
        params.kafkaTrafficBrokers = "localhost:9092";

        var result = invokeCreateS3TupleWriterIfConfigured(params);

        Assertions.assertNotNull(result);
        Assertions.assertInstanceOf(AutoCloseable.class, result);
        ((AutoCloseable) result).close();
    }
}
