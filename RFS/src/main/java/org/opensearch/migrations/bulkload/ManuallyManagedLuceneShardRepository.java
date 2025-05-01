package org.opensearch.migrations.bulkload;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.lucene.LuceneIndexReader;
import org.opensearch.migrations.bulkload.lucene.version_9.IndexReader9;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ManuallyManagedLuceneShardRepository extends LuceneBasedDocumentRepository {

    private final static ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Getter
    public enum CommandType {
        EXPECTED_DURATIONS("getExpectedDurations"),
        SHARD_SETUP_TIME_IN_SECONDS("getShardSetupTime"),
        INDEX_NAMES("getIndexNames"),
        NUM_SHARDS("getNumShardsForIndex"),
        GET_INDEX_METADATA("getIndexMetadata"),
        DOWNLOAD_SIZE_IN_BYTES("getDownloadSizeInBytes"),
        DOWNLOAD("downloadIndexShard");

        private final String value;

        CommandType(String value) {
            this.value = value;
        }
    }

    protected final String processInvocationCommand;
    private final String configStr;
    private final Map<CommandType, Duration> expectedMaxCommandDurationMap;

    public ManuallyManagedLuceneShardRepository(String processInvocationCommand, String configStr) {
        this.processInvocationCommand = processInvocationCommand;
        this.configStr = configStr;
        expectedMaxCommandDurationMap = runProcessAndParse(CommandType.EXPECTED_DURATIONS, Duration.ofSeconds(10));
    }

    protected Stream<String> generateArgs(String command, String[] extraArgs) {
        return Stream.concat(
            Stream.of(
                "--config",
                configStr,
                command
            ),
            Optional.ofNullable(extraArgs).stream().flatMap(Arrays::stream));
    }

    @NonNull
    public ProcessBuilder setupProcess(String command, String[] extraArgs) {
        ProcessBuilder processBuilder = new ProcessBuilder(
            Stream.concat(Stream.of(processInvocationCommand),
                generateArgs(command, extraArgs))
                .toArray(String[]::new)
        );
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        return processBuilder;
    }

    public String runProcess(CommandType command, Duration timeout, String... extraArgs)
        throws IOException, InterruptedException
    {
        var processBuilder = setupProcess(command.toString(), extraArgs);
        var p = processBuilder.start();
        p.wait(timeout.toMillis());
        try (var isr = new InputStreamReader(p.getInputStream());
             var br = new BufferedReader(isr)) {
            return br.lines().collect(Collectors.joining("\n"));
        }
    }

    @SneakyThrows
    public <T> T runProcessAndParse(CommandType command, Duration timeout, String... extraArgs) {
        var output = runProcess(command, timeout, extraArgs);
        return OBJECT_MAPPER.readValue(output, new TypeReference<>() {});
    }

    public <T> T runProcessAndParse(CommandType command, String... extraArgs) {
        return runProcessAndParse(command, expectedMaxCommandDurationMap.get(command), extraArgs);
    }

    @Override
    public Duration expectedMaxShardSetupTime() {
        return Duration.ofSeconds(runProcessAndParse(CommandType.SHARD_SETUP_TIME_IN_SECONDS));
    }

    @Override
    @SneakyThrows
    public Stream<String> getIndexNamesInSnapshot() {
        return runProcessAndParse(CommandType.INDEX_NAMES);
    }

    @Override
    public int getNumShards(String indexName) {
        return runProcessAndParse(CommandType.NUM_SHARDS, indexName);
    }

    @Override
    public long getShardSizeInBytes(String indexName, Integer shard) {
        return runProcessAndParse(CommandType.DOWNLOAD_SIZE_IN_BYTES, indexName);
    }

    @Override
    public LuceneIndexReader getReader(String indexName, int shard) {
        Map<String, String> metadata = runProcessAndParse(CommandType.GET_INDEX_METADATA, indexName);
        var luceneDir = Path.of(runProcessAndParse(CommandType.DOWNLOAD, indexName, Integer.toString(shard)));
        var softDeleteField = metadata.get("softDeleteField");
        return new IndexReader9(luceneDir, softDeleteField!=null, softDeleteField);
    }
}
