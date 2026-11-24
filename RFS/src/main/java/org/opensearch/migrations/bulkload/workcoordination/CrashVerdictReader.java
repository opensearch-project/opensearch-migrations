package org.opensearch.migrations.bulkload.workcoordination;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads the JSONL crash-verdict file written by the pod's sidecar crash witness.  Each line is one
 * record per bulk-loader container termination:
 * {@code {"finishedAt": <ISO-8601 or epoch seconds>, "exitCode": <int>, "reason": <string>,
 * "restartCount": <int>}}.
 *
 * <p>The OOM rule: a termination is OOM evidence iff the exit code is 3
 * ({@code ExitOnOutOfMemoryError}) OR kubelet reported {@code reason: OOMKilled}.  A bare 137 is
 * SIGKILL of unknown provenance (kill -9, eviction, the wrapper's force-kill) and is deliberately
 * NOT treated as OOM.
 */
@Slf4j
public class CrashVerdictReader {
    /** The JVM-fixed exit code produced by {@code -XX:+ExitOnOutOfMemoryError} on heap OOM. */
    public static final int HEAP_OOM_EXIT_CODE = 3;
    public static final String OOM_KILLED_REASON = "OOMKilled";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CrashVerdictReader() {}

    public record VerdictRecord(long finishedAtEpochSeconds, Integer exitCode, String reason, Integer restartCount) {
        public boolean isOom() {
            return (exitCode != null && exitCode == HEAP_OOM_EXIT_CODE) || OOM_KILLED_REASON.equals(reason);
        }
    }

    /** Parse every well-formed line; malformed lines are logged and skipped (torn tail writes). */
    public static List<VerdictRecord> parseJsonlLines(List<String> lines) {
        var records = new ArrayList<VerdictRecord>();
        for (var line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            try {
                var node = MAPPER.readTree(line);
                records.add(new VerdictRecord(
                    parseFinishedAtEpochSeconds(node.path("finishedAt")),
                    node.path("exitCode").isNumber() ? node.path("exitCode").asInt() : null,
                    node.path("reason").isTextual() ? node.path("reason").asText() : null,
                    node.path("restartCount").isNumber() ? node.path("restartCount").asInt() : null));
            } catch (IOException | RuntimeException e) {
                log.atWarn().setCause(e).setMessage("Skipping malformed crash-verdict line: {}")
                    .addArgument(line).log();
            }
        }
        return records;
    }

    public static List<VerdictRecord> readVerdicts(Path verdictFile) {
        if (verdictFile == null || !Files.exists(verdictFile)) {
            return List.of();
        }
        try {
            return parseJsonlLines(Files.readAllLines(verdictFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.atWarn().setCause(e).setMessage("Could not read crash-verdict file {}")
                .addArgument(verdictFile).log();
            return List.of();
        }
    }

    /**
     * The newest record that could cover a lease taken at {@code leaseAcquiredAtEpochSeconds}:
     * {@code finishedAt >= leaseAcquiredAt - skew}.  The skew allowance accounts for the kubelet
     * clock (finishedAt) and the coordinator clock (leaseAcquiredAt) differing.
     */
    public static Optional<VerdictRecord> newestCoveringVerdict(
        List<VerdictRecord> verdicts, long leaseAcquiredAtEpochSeconds, long skewAllowanceSeconds
    ) {
        return verdicts.stream()
            .filter(v -> v.finishedAtEpochSeconds() >= leaseAcquiredAtEpochSeconds - skewAllowanceSeconds)
            .max(Comparator.comparingLong(VerdictRecord::finishedAtEpochSeconds));
    }

    static long parseFinishedAtEpochSeconds(JsonNode finishedAt) {
        if (finishedAt.isNumber()) {
            return finishedAt.asLong();
        }
        if (finishedAt.isTextual()) {
            return Instant.parse(finishedAt.asText()).getEpochSecond();
        }
        throw new IllegalArgumentException("finishedAt is missing or not a timestamp: " + finishedAt);
    }
}
