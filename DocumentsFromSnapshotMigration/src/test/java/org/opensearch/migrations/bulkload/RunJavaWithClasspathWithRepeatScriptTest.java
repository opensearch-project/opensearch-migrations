package org.opensearch.migrations.bulkload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral tests for runJavaWithClasspathWithRepeat.sh: the Docker entrypoint
 * wrapper that re-execs the Java process. The wrapper must translate the
 * RfsMigrateDocuments exit codes correctly:
 *
 *   exit 3 (NO_WORK_LEFT)      -> wrapper exits 0 (Pod Completed, no restart loop)
 *   exit 4 (NO_WORK_AVAILABLE) -> wrapper sleeps with backoff, then re-execs
 *   exit 0 (work done)         -> wrapper re-execs immediately
 *   exit other (real failure)  -> wrapper propagates the code
 *
 * Tests run by invoking the real script with a stubbed `runJavaWithClasspath.sh`
 * child placed on a temp PATH that records each invocation.
 */
@DisabledOnOs(OS.WINDOWS) // /bin/sh-based wrapper
class RunJavaWithClasspathWithRepeatScriptTest {

    private static final Path SCRIPT = locateScript();

    private static Path locateScript() {
        // Resolve from the project working directory in a robust way: the test
        // is run from .../DocumentsFromSnapshotMigration so the script lives
        // at docker/runJavaWithClasspathWithRepeat.sh relative to it.
        Path candidate = Paths.get("docker", "runJavaWithClasspathWithRepeat.sh").toAbsolutePath();
        if (Files.exists(candidate)) {
            return candidate;
        }
        // Fallback: walk up one level (in case tests are invoked from repo root).
        Path alt = Paths.get("DocumentsFromSnapshotMigration", "docker", "runJavaWithClasspathWithRepeat.sh")
            .toAbsolutePath();
        if (Files.exists(alt)) {
            return alt;
        }
        throw new IllegalStateException("Cannot locate runJavaWithClasspathWithRepeat.sh; tried "
            + candidate + " and " + alt);
    }

    /**
     * Writes a fake `/rfs-app/runJavaWithClasspath.sh` shim that records each
     * invocation in a counter file and exits with the next code from a
     * pre-supplied list. Returns the directory that should be prepended to PATH
     * (for /rfs-app substitution we rebind via a sed-replaced copy of the
     * script — see {@link #stagedScript(Path, Path)}).
     */
    private static Path writeChildShim(Path tempDir, String exitCodesCsv, Path counterFile) throws IOException {
        Path child = tempDir.resolve("fake-runJavaWithClasspath.sh");
        String shim = "#!/bin/sh\n"
            + "n=$(cat \"" + counterFile.toAbsolutePath() + "\" 2>/dev/null || echo 0)\n"
            + "n=$((n + 1))\n"
            + "echo $n > \"" + counterFile.toAbsolutePath() + "\"\n"
            + "set -- " + exitCodesCsv.replace(",", " ") + "\n"
            + "i=$n\n"
            + "while [ $i -gt 1 ]; do shift; i=$((i - 1)); done\n"
            + "exit ${1:-0}\n";
        Files.writeString(child, shim);
        try {
            Files.setPosixFilePermissions(child, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE
            ));
        } catch (UnsupportedOperationException ignored) {
            // not a POSIX file system; chmod handled by sh
        }
        return child;
    }

    /**
     * Produces a copy of the production script with the hardcoded
     * /rfs-app/runJavaWithClasspath.sh path rewritten to point at our test
     * shim. The production script does not parameterize that path, so this
     * is the cleanest way to test the wrapper without breaking its
     * production behavior.
     */
    private static Path stagedScript(Path tempDir, Path childShim) throws IOException {
        String original = Files.readString(SCRIPT);
        String rewritten = original.replace(
            "/rfs-app/runJavaWithClasspath.sh",
            childShim.toAbsolutePath().toString()
        );
        Path staged = tempDir.resolve("repeat-under-test.sh");
        Files.writeString(staged, rewritten);
        try {
            Files.setPosixFilePermissions(staged, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
            ));
        } catch (UnsupportedOperationException ignored) {
            // not a POSIX file system
        }
        return staged;
    }

    private static int runScript(Path staged) throws IOException, InterruptedException {
        // Use a tiny backoff to keep the NO_WORK_AVAILABLE test fast.
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", staged.toAbsolutePath().toString());
        pb.environment().put("NO_WORK_BACKOFF_INITIAL_SECONDS", "0");
        pb.environment().put("NO_WORK_BACKOFF_MAX_SECONDS", "0");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new AssertionError("repeat script did not exit within 30s");
        }
        return p.exitValue();
    }

    @Test
    void exit3FromChildPropagatesAsThreeWithoutRetry(@TempDir Path tempDir) throws Exception {
        Path counter = tempDir.resolve("counter");
        Path shim = writeChildShim(tempDir, "3", counter);
        Path staged = stagedScript(tempDir, shim);

        assertEquals(3, runScript(staged),
            "exit 3 from child should be propagated as wrapper exit 3 so the orchestrator can apply backoff");
        assertEquals("1", Files.readString(counter).trim(), "child should be invoked exactly once");
    }

    @Test
    void exit4FromChildBackoffsAndRetries(@TempDir Path tempDir) throws Exception {
        Path counter = tempDir.resolve("counter");
        // First call -> 4 (no work available, retry), second call -> 3 (no work left, propagate)
        Path shim = writeChildShim(tempDir, "4,3", counter);
        Path staged = stagedScript(tempDir, shim);

        assertEquals(3, runScript(staged),
            "exit 4 then exit 3 should result in wrapper exit 3 after retry");
        assertEquals("2", Files.readString(counter).trim(),
            "child should be invoked twice: once for the 4, once for the 3");
    }

    @Test
    void exit0FromChildRestartsImmediatelyUntilTerminalCode(@TempDir Path tempDir) throws Exception {
        Path counter = tempDir.resolve("counter");
        // Three "did work, restart" cycles, then a terminal "no work left".
        Path shim = writeChildShim(tempDir, "0,0,0,3", counter);
        Path staged = stagedScript(tempDir, shim);

        assertEquals(3, runScript(staged),
            "exit 0 cycles followed by exit 3 should end with wrapper exit 3");
        assertEquals("4", Files.readString(counter).trim(),
            "child should be invoked four times: three exit-0 cycles plus the terminal exit 3");
    }

    @Test
    void nonZeroNonTerminalCodePropagates(@TempDir Path tempDir) throws Exception {
        Path counter = tempDir.resolve("counter");
        Path shim = writeChildShim(tempDir, "1", counter);
        Path staged = stagedScript(tempDir, shim);

        int actual = runScript(staged);
        assertEquals(1, actual, "exit 1 from child should propagate verbatim");
        assertTrue(Files.exists(counter), "counter file should exist after one invocation");
        assertEquals("1", Files.readString(counter).trim(), "child should be invoked exactly once on hard failure");
    }
}
