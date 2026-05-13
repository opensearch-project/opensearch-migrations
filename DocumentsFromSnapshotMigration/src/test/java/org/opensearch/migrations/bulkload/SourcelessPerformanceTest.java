package org.opensearch.migrations.bulkload;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.FileSystemSnapshotCreator;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.worker.SnapshotRunner;
import org.opensearch.migrations.cluster.SnapshotReaderRegistry;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.lifecycle.Startables;

@Slf4j
@Tag("isolatedTest")
public class SourcelessPerformanceTest extends SourceTestBase {

    @TempDir
    private File localDirectory;

    private static final String INDEX_NAME = "enron_archive";
    private static final String SNAPSHOT_NAME = "perf_snap";
    private static final String SNAPSHOT_REPO = "perf_repo";
    private static final int NUM_DOCS = 120_000;
    private static final int BULK_BATCH = 500;
    private static final int VOCAB_SIZE = 50_000;

    @Test
    @SneakyThrows
    public void testSourcelessPerformance_withOptimizations() {
        try (
            var sourceCluster = new SearchClusterContainer(SearchClusterContainer.ES_V8_11);
            var targetCluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)
        ) {
            Startables.deepStart(sourceCluster, targetCluster).join();
            var sourceOps = new ClusterOperations(sourceCluster);
            var targetOps = new ClusterOperations(targetCluster);

            log.info("========================================");
            log.info("  OPTIMIZED SOURCELESS PERF TEST");
            log.info("  Docs: {}, Vocab: {}", NUM_DOCS, VOCAB_SIZE);
            log.info("  Changes: parallel sidecar pre-build,");
            log.info("    lock-free reads, no text copy_to");
            log.info("========================================");

            createIndex(sourceOps);
            Instant loadStart = Instant.now();
            loadDocs(sourceCluster.getUrl());
            log.info("Load: {}s", Duration.between(loadStart, Instant.now()).getSeconds());

            var client = new RestClient(ConnectionContextTestParams.builder()
                .host(sourceCluster.getUrl()).build().toConnectionContext());
            var ctx = DocumentMigrationTestContext.factory().noOtelTracking().createUnboundRequestContext();
            client.post(INDEX_NAME + "/_forcemerge?max_num_segments=1&flush=true", "", ctx);
            var segResp = client.get("_cat/segments/" + INDEX_NAME + "?v&h=index,shard,segment,docs.count,size", ctx);
            log.info("Segments:\n{}", segResp.body);

            var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
            var sourceClientFactory = new OpenSearchClientFactory(ConnectionContextTestParams.builder()
                .host(sourceCluster.getUrl()).insecure(true).build().toConnectionContext());
            var snapshotCreator = new FileSystemSnapshotCreator(SNAPSHOT_NAME, SNAPSHOT_REPO,
                sourceClientFactory.determineVersionAndCreate(),
                SearchClusterContainer.CLUSTER_SNAPSHOT_DIR, List.of(),
                snapshotContext.createSnapshotCreateContext());
            SnapshotRunner.runAndWaitForCompletion(snapshotCreator);
            sourceCluster.copySnapshotData(localDirectory.toString());

            var fileFinder = SnapshotReaderRegistry.getSnapshotFileFinder(
                sourceCluster.getContainerVersion().getVersion(), true);
            var sourceRepo = new FileSystemRepo(localDirectory.toPath(), fileFinder);
            targetOps.createIndex(INDEX_NAME, "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0}}");

            log.info("=== STARTING OPTIMIZED MIGRATION ===");
            Instant migrationStart = Instant.now();
            var testCtx = DocumentMigrationTestContext.factory().noOtelTracking();
            var runCounter = new AtomicInteger();

            var result = waitForRfsCompletion(() ->
                SourcelessMigrationTest.migrateDocumentsSequentiallyWithSourceless(
                    sourceRepo, SNAPSHOT_NAME, List.of(INDEX_NAME),
                    targetCluster, runCounter, new Random(1), testCtx,
                    sourceCluster.getContainerVersion().getVersion(),
                    targetCluster.getContainerVersion().getVersion())
            );

            long secs = Duration.between(migrationStart, Instant.now()).getSeconds();
            long millis = Duration.between(migrationStart, Instant.now()).toMillis();
            log.info("========================================");
            log.info("  RESULT: {}ms ({}s)", millis, secs);
            log.info("  Rate: {} docs/sec", millis > 0 ? (NUM_DOCS * 1000L) / millis : "inf");
            log.info("  (Previous baseline: 78s / 1538 docs/sec)");
            log.info("========================================");

            targetOps.refresh();
            var targetClient = new RestClient(ConnectionContextTestParams.builder()
                .host(targetCluster.getUrl()).build().toConnectionContext());
            log.info("Target: {}", targetClient.get(INDEX_NAME + "/_count", ctx).body);
        }
    }

    private void createIndex(ClusterOperations ops) {
        ops.createIndex(INDEX_NAME, "{\n"
            + "\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0,\"refresh_interval\":\"-1\",\n"
            + "  \"analysis\":{\"filter\":{\"alcatraz_pattern_capture\":{\"type\":\"pattern_capture\",\"preserve_original\":true,\"patterns\":[\"([^ -]+)\"]}},\n"
            + "    \"analyzer\":{\"alcatraz_tokenized_string\":{\"type\":\"custom\",\"tokenizer\":\"standard\",\"filter\":[\"alcatraz_pattern_capture\",\"lowercase\",\"asciifolding\",\"stop\"]}}}},\n"
            + "\"mappings\":{\"_source\":{\"excludes\":[\"subj\",\"texts\",\"files.content\",\"files.path\",\"actions.content\"]},\n"
            + "  \"properties\":{\n"
            + "    \"doc_type\":{\"type\":\"keyword\"},\"gtid\":{\"type\":\"keyword\",\"doc_values\":false},\"gcid\":{\"type\":\"keyword\"},\"stime\":{\"type\":\"date\"},\n"
            + "    \"net\":{\"type\":\"keyword\"},\"chan\":{\"type\":\"keyword\"},\n"
            + "    \"subnets\":{\"type\":\"keyword\",\"index\":false,\"doc_values\":false,\"copy_to\":[\"net\"]},\n"
            + "    \"subchans\":{\"type\":\"keyword\",\"index\":false,\"doc_values\":false,\"copy_to\":[\"chan\"]},\n"
            + "    \"fsubj\":{\"type\":\"keyword\",\"index\":false},\n"
            + "    \"subj\":{\"type\":\"text\",\"analyzer\":\"alcatraz_tokenized_string\",\"norms\":false},\n"
            + "    \"nsize\":{\"type\":\"long\"},\"lcnt\":{\"type\":\"integer\"},\n"
            + "    \"iusers\":{\"properties\":{\"from\":{\"properties\":{\n"
            + "      \"user\":{\"type\":\"keyword\",\"index\":false,\"doc_values\":false,\"copy_to\":[\"users.from.user\",\"users.all.user\"]},\n"
            + "      \"display\":{\"type\":\"keyword\",\"index\":false,\"doc_values\":false,\"copy_to\":[\"users.from.display\",\"users.all.display\"]},\n"
            + "      \"domain\":{\"type\":\"keyword\",\"index\":false,\"doc_values\":false,\"copy_to\":[\"users.from.domain\",\"users.all.domain\"]}}},\n"
            + "      \"to\":{\"properties\":{\n"
            + "      \"user\":{\"type\":\"keyword\",\"index\":false,\"doc_values\":false,\"copy_to\":[\"users.to.user\",\"users.all.user\"]},\n"
            + "      \"display\":{\"type\":\"keyword\",\"index\":false,\"doc_values\":false,\"copy_to\":[\"users.to.display\",\"users.all.display\"]},\n"
            + "      \"domain\":{\"type\":\"keyword\",\"index\":false,\"doc_values\":false,\"copy_to\":[\"users.to.domain\",\"users.all.domain\"]}}}}},\n"
            + "    \"users\":{\"properties\":{\n"
            + "      \"from\":{\"properties\":{\"user\":{\"type\":\"keyword\"},\"display\":{\"type\":\"keyword\"},\"domain\":{\"type\":\"keyword\"}}},\n"
            + "      \"to\":{\"properties\":{\"user\":{\"type\":\"keyword\"},\"display\":{\"type\":\"keyword\"},\"domain\":{\"type\":\"keyword\"}}},\n"
            + "      \"all\":{\"properties\":{\"user\":{\"type\":\"keyword\"},\"display\":{\"type\":\"keyword\"},\"domain\":{\"type\":\"keyword\"}}}}},\n"
            + "    \"texts\":{\"properties\":{\"user\":{\"properties\":{\"content\":{\"type\":\"text\",\"analyzer\":\"alcatraz_tokenized_string\",\"norms\":false}}},\n"
            + "      \"sys\":{\"properties\":{\"content\":{\"type\":\"text\",\"analyzer\":\"alcatraz_tokenized_string\",\"norms\":false}}}}},\n"
            + "    \"files\":{\"properties\":{\"path\":{\"type\":\"text\",\"analyzer\":\"alcatraz_tokenized_string\",\"norms\":false},\"name\":{\"type\":\"keyword\"},\"size\":{\"type\":\"long\"},\n"
            + "      \"content\":{\"type\":\"text\",\"analyzer\":\"alcatraz_tokenized_string\",\"norms\":false}}},\n"
            + "    \"actions\":{\"properties\":{\"type\":{\"type\":\"keyword\"},\"content\":{\"type\":\"text\",\"analyzer\":\"alcatraz_tokenized_string\",\"norms\":false}}}\n"
            + "}}}");
    }

    @SneakyThrows
    private void loadDocs(String url) {
        var client = new RestClient(ConnectionContextTestParams.builder().host(url).build().toConnectionContext());
        var ctx = DocumentMigrationTestContext.factory().noOtelTracking().createUnboundRequestContext();
        Random rng = new Random(42);
        String[] vocab = new String[VOCAB_SIZE];
        for (int i = 0; i < VOCAB_SIZE; i++) {
            int len = 3 + rng.nextInt(10);
            StringBuilder w = new StringBuilder(len);
            for (int c = 0; c < len; c++) w.append((char)('a' + rng.nextInt(26)));
            vocab[i] = w.toString();
        }
        String[] domains = {"enron.com","ect.enron.com","dynegy.com","calpine.com"};
        String[] names = {"allen","smith","johnson","williams","brown","jones","garcia","miller"};
        int loaded = 0;
        Instant lastLog = Instant.now();
        for (int batch = 0; batch < NUM_DOCS / BULK_BATCH; batch++) {
            StringBuilder bulk = new StringBuilder(BULK_BATCH * 25000);
            for (int d = 0; d < BULK_BATCH; d++) {
                String id = UUID.randomUUID().toString().replace("-","");
                StringBuilder body = new StringBuilder(400*8);
                for (int w = 0; w < 300 + rng.nextInt(200); w++) { if(w>0) body.append(' '); body.append(vocab[rng.nextInt(VOCAB_SIZE)]); }
                StringBuilder sys = new StringBuilder(100*8);
                for (int w = 0; w < 50 + rng.nextInt(50); w++) { if(w>0) sys.append(' '); sys.append(vocab[rng.nextInt(VOCAB_SIZE)]); }
                StringBuilder fc = new StringBuilder(200*8);
                for (int w = 0; w < 100 + rng.nextInt(100); w++) { if(w>0) fc.append(' '); fc.append(vocab[rng.nextInt(VOCAB_SIZE)]); }
                String from = names[rng.nextInt(names.length)]+"@"+domains[rng.nextInt(domains.length)];
                String to = names[rng.nextInt(names.length)]+"@"+domains[rng.nextInt(domains.length)];
                bulk.append("{\"index\":{\"_index\":\"").append(INDEX_NAME).append("\",\"_id\":\"").append(id).append("\"}}\n");
                bulk.append("{\"doc_type\":\"idoc\",\"gtid\":\"").append(id)
                    .append("\",\"gcid\":\"").append(UUID.randomUUID().toString().replace("-",""))
                    .append("\",\"stime\":\"2001-06-15T10:00:00Z\",\"net\":\"enron.com\",\"chan\":\"email\"")
                    .append(",\"subnets\":[\"enron.com\"],\"subchans\":[\"email\"]")
                    .append(",\"fsubj\":\"subj ").append(loaded+d).append("\",\"subj\":\"Subject ").append(loaded+d).append(" Important\"")
                    .append(",\"nsize\":").append(1000+rng.nextInt(99000)).append(",\"lcnt\":").append(1+rng.nextInt(20))
                    .append(",\"iusers\":{\"from\":{\"user\":\"").append(from).append("\",\"display\":\"").append(from.split("@")[0])
                    .append("\",\"domain\":\"").append(from.split("@")[1]).append("\"},\"to\":{\"user\":\"").append(to)
                    .append("\",\"display\":\"").append(to.split("@")[0]).append("\",\"domain\":\"").append(to.split("@")[1]).append("\"}}")
                    .append(",\"texts\":{\"user\":{\"content\":\"").append(body).append("\"},\"sys\":{\"content\":\"").append(sys).append("\"}}")
                    .append(",\"files\":[{\"path\":\"/docs/file_").append(loaded+d).append(".pdf\",\"name\":\"f.pdf\",\"size\":1024,\"content\":\"").append(fc).append("\"}]")
                    .append(",\"actions\":[{\"type\":\"review\",\"content\":\"").append(body,0,Math.min(150,body.length())).append("\"}]}\n");
            }
            client.post("_bulk", bulk.toString(), ctx);
            loaded += BULK_BATCH;
            if (Duration.between(lastLog, Instant.now()).getSeconds() >= 15) {
                log.info("  Loaded {}/{}", loaded, NUM_DOCS); lastLog = Instant.now();
            }
        }
        log.info("  Loaded all {} docs", loaded);
    }
}
