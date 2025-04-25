package org.opensearch.migrations.utils.kafka;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Future;
import java.util.zip.GZIPInputStream;

import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

import static org.opensearch.migrations.trafficcapture.kafkaoffloader.KafkaConfig.buildKafkaProperties;

@Slf4j
public class KafkaLoader {
    public static final String TOPIC_NAME = "logging-traffic-topic";


    public void loadRecordsToKafkaFromCompressedFile(
            String filename,
            String kafkaPropertiesFile,
            String kafkaConnection,
            String kafkaClientId,
            boolean mskAuthEnabled
    ) throws Exception {
        var kafkaProperties = buildKafkaProperties(kafkaPropertiesFile, kafkaConnection, kafkaClientId, mskAuthEnabled);
        var kafkaProducer = new KafkaProducer(kafkaProperties);
        BufferedReader bufferedReader = createBufferedReaderFromFile(filename);
        readLinesAndSendToKafka(bufferedReader, kafkaProducer, 500);
    }

    public void readLinesAndSendToKafka(BufferedReader reader, Producer<String, byte[]> producer, int batchSize) {
        List<Future<RecordMetadata>> futures = new ArrayList<>();
        int i = 0;
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                var recordId = "KEY_" + i;
                var byteArray = Base64.getDecoder().decode(line);
                futures.add(producer.send(new ProducerRecord<>(TOPIC_NAME, recordId, byteArray)));
                i++;

                // Flush batch
                if (i % batchSize == 0) {
                    waitForFutures(futures);
                    log.info("Sent " + i + " messages to kafka topic " + TOPIC_NAME);
                    futures.clear();
                }
            }
            log.info("End of stream reached");
            waitForFutures(futures);
            log.info("Sent total of " + i + " messages to kafka topic " + TOPIC_NAME);
        } catch (Exception e) {
            throw Lombok.sneakyThrow(e);
        }
    }

    private void waitForFutures(List<Future<RecordMetadata>> futures) throws Exception {
        for (var future : futures) {
            future.get();
        }
    }

    private BufferedReader createBufferedReaderFromFile(String filename) throws Exception {
        var compressedIs = new FileInputStream(filename);
        var is = new GZIPInputStream(compressedIs);
        var isr = new InputStreamReader(is);
        try {
            return new BufferedReader(isr);
        } catch (Exception e) {
            try {
                isr.close();
            } catch (Exception e2) {
                log.atError().setCause(e2).setMessage("Caught exception while closing InputStreamReader that " +
                        "was in response to an earlier thrown exception.  Swallowing the inner exception and " +
                        "throwing the original one.").log();
            }
            throw e;
        }
    }
}
