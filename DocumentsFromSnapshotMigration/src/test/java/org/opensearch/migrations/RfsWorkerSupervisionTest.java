package org.opensearch.migrations;

import org.opensearch.migrations.jcommander.JsonCommandLineParser;

import com.beust.jcommander.ParameterException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The worker's side of the in-container supervision contract: the supervisor's per-launch arguments
 * parse after the inline JSON config, and its instance id becomes the worker id's suffix so crash
 * reports attribute to exactly the lease the worker held.
 */
class RfsWorkerSupervisionTest {

    @Test
    void instanceIdBecomesTheWorkerIdSuffix() {
        var args = new RfsMigrateDocuments.Args();
        Assertions.assertFalse(RfsMigrateDocuments.resolveWorkerId(args).endsWith("_1795572031-1"));
        args.workerInstanceId = "1795572031-1";
        var workerId = RfsMigrateDocuments.resolveWorkerId(args);
        Assertions.assertTrue(workerId.endsWith("_1795572031-1"), workerId);
    }

    @Test
    void supervisorFlagsParseAfterTheInlineJsonDocument() {
        // The production launch shape: the pod spec supplies ---INLINE-JSON <doc> and the
        // supervisor appends its per-launch flags after it (this exact argv crashed the first
        // EKS ladder run when the parser still rejected anything beyond [flag, json, command]).
        var args = new RfsMigrateDocuments.Args();
        JsonCommandLineParser.newBuilder().addObject(args).build().parse(new String[] {
            "---INLINE-JSON",
            "{\"snapshotName\":\"s\",\"targetHost\":\"http://localhost:9200\"}",
            "--worker-instance-id", "1795584694-3",
            "--predecessor-crashes", "1795584694-2=3",
            "--sweep-only"});
        Assertions.assertEquals("s", args.snapshotName);
        Assertions.assertEquals("1795584694-3", args.workerInstanceId);
        Assertions.assertEquals("1795584694-2=3", args.predecessorCrashes);
        Assertions.assertTrue(args.sweepOnly);
    }

    @Test
    void instanceIdMustNotContainTheWorkerIdSeparator() {
        var args = new RfsMigrateDocuments.Args();
        JsonCommandLineParser.newBuilder().addObject(args).build().parse(new String[] {
            "--snapshot-name", "s", "--target-host", "http://localhost:9200",
            "--snapshot-local-dir", "/tmp/s", "--lucene-dir", "/tmp/l",
            "--worker-instance-id", "bad_id"});
        Assertions.assertThrows(ParameterException.class, () -> RfsMigrateDocuments.validateArgs(args));
    }
}
