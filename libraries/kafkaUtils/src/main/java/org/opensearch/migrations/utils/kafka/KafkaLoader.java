package org.opensearch.migrations.utils.kafka;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.zip.GZIPInputStream;

import org.opensearch.migrations.trafficcapture.kafkaoffloader.KafkaConfig;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

@Slf4j
public class KafkaLoader {
    private static final String DELIMITER = "\\|";
    private String kafkaPropertiesFile;
    private String kafkaConnection;
    private String kafkaClientId;
    private boolean mskAuthEnabled;

    public static class InvalidKafkaExportFormat extends Exception {
        public InvalidKafkaExportFormat(String message) {
            super(message);
        }
    }

    public KafkaLoader(String kafkaPropertiesFile, String kafkaConnection, String kafkaClientId, boolean mskAuthEnabled) {
        this.kafkaPropertiesFile = kafkaPropertiesFile;
        this.kafkaConnection = kafkaConnection;
        this.kafkaClientId = kafkaClientId;
        this.mskAuthEnabled = mskAuthEnabled;
    }

    public void loadRecordsToKafkaFromCompressedFile(String fileName, String topicName, int batchSize) throws Exception {
        var kafkaProperties =
            KafkaConfig.buildKafkaProperties(kafkaPropertiesFile, kafkaConnection, kafkaClientId, mskAuthEnabled);
        try (var kafkaProducer = new KafkaProducer<String, byte[]>(kafkaProperties)) {
            BufferedReader bufferedReader = createBufferedReaderFromFile(fileName);
            readLinesAndSendToKafka(bufferedReader, kafkaProducer, topicName, batchSize);
        }
    }

    public void readLinesAndSendToKafka(BufferedReader reader, Producer<String, byte[]> producer, String topicName, int batchSize) throws Exception {
        List<Future<RecordMetadata>> futures = new ArrayList<>();
        int i = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            String[] splitString = line.split(DELIMITER);
            if (splitString.length != 2) {
                throw new InvalidKafkaExportFormat("Expected record in format '<key>|<value>' but did not find the correct number of delimiters");
            }
            var recordId = splitString[0];
            var byteArray = Base64.getDecoder().decode(splitString[1]);
            futures.add(producer.send(new ProducerRecord<>(topicName, recordId, byteArray)));
            i++;

            // Flush batch
            if (i % batchSize == 0) {
                waitForFutures(futures);
                log.info("Sent " + i + " messages to kafka topic " + topicName);
                futures.clear();
            }
        }
        log.info("End of stream reached");
        waitForFutures(futures);
        log.info("Sent total of " + i + " messages to kafka topic " + topicName);
    }

    private void waitForFutures(List<Future<RecordMetadata>> futures) throws ExecutionException, InterruptedException {
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
                log.atError()
                    .setCause(e2)
                    .setMessage("Caught exception while closing InputStreamReader that was in response to an earlier thrown exception. Swallowing the inner exception and throwing the original one.")
                    .log();
            }
            throw e;
        }
    }
}
