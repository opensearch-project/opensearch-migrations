package org.opensearch.migrations.bulkload.common;

import java.nio.file.Path;
import java.nio.file.Paths;

import lombok.RequiredArgsConstructor;

/**
 * References to pre-built snapshot test fixtures stored in RFS/test-resources/.
 * Resolves paths relative to the root project directory so this works from any submodule.
 */
public class TestResources {
    @RequiredArgsConstructor
    public static class Snapshot {
        public final Path dir;
        public final String name;
    }

    public static final Snapshot SNAPSHOT_ES_5_6;
    public static final Snapshot SNAPSHOT_ES_6_8;
    public static final Snapshot SNAPSHOT_ES_6_8_MERGED;
    public static final Snapshot SNAPSHOT_ES_7_10_BWC_CHECK;
    public static final Snapshot SNAPSHOT_ES_7_10_W_SOFT;
    public static final Snapshot SNAPSHOT_ES_7_10_WO_SOFT;

    /** Golden files directory for document/metadata extraction tests */
    public static final Path GOLDEN_DIR;

    private static Path findRfsDir() {
        Path cwd = Paths.get(System.getProperty("user.dir"));
        // If we're already in RFS, use cwd directly
        if (cwd.getFileName().toString().equals("RFS")) {
            return cwd;
        }
        // Otherwise, resolve relative to cwd (works from SnapshotReader, DocumentsFromSnapshotMigration, etc.)
        return cwd.resolve("../RFS").normalize();
    }

    static {
        Path rfsBaseDir = findRfsDir();

        GOLDEN_DIR = rfsBaseDir.resolve("test-resources/golden");

        SNAPSHOT_ES_5_6 = new Snapshot(
            rfsBaseDir.resolve(Paths.get("test-resources", "snapshots", "ES_5_6_Updates_Deletes")),
            "rfs_snapshot"
        );

        SNAPSHOT_ES_6_8 = new Snapshot(
            rfsBaseDir.resolve(Paths.get("test-resources", "snapshots", "ES_6_8_Updates_Deletes_Native")),
            "rfs_snapshot"
        );

        SNAPSHOT_ES_6_8_MERGED = new Snapshot(
            rfsBaseDir.resolve(Paths.get("test-resources", "snapshots", "ES_6_8_Updates_Deletes_Merged")),
            "rfs_snapshot"
        );

        SNAPSHOT_ES_7_10_BWC_CHECK = new Snapshot(
            rfsBaseDir.resolve(Paths.get("test-resources", "snapshots", "ES_7_10_BWC_Check")),
            "rfs-snapshot"
        );

        SNAPSHOT_ES_7_10_W_SOFT = new Snapshot(
            rfsBaseDir.resolve(Paths.get("test-resources", "snapshots", "ES_7_10_Updates_Deletes_w_Soft")),
            "rfs_snapshot"
        );

        SNAPSHOT_ES_7_10_WO_SOFT = new Snapshot(
            rfsBaseDir.resolve(Paths.get("test-resources", "snapshots", "ES_7_10_Updates_Deletes_wo_Soft")),
            "rfs_snapshot"
        );
    }
}
