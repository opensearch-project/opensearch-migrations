package com.rfs.common;

import java.nio.file.Path;
import java.nio.file.Paths;

import lombok.RequiredArgsConstructor;

public class TestResources {
    @RequiredArgsConstructor
    public static class Snapshot {
        public final Path dir;
        public final String name;
    }

    public static final Snapshot SNAPSHOT_ES_7_10_W_SOFT;
    public static final Snapshot SNAPSHOT_ES_7_10_WO_SOFT;

    static {
        Path rfsBaseDir = Paths.get(System.getProperty("user.dir"));

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
