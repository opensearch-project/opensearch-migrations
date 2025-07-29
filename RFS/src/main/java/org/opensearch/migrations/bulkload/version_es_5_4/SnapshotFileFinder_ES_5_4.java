package org.opensearch.migrations.bulkload.version_es_5_4;

import java.util.regex.Pattern;

import org.opensearch.migrations.bulkload.common.BaseSnapshotFileFinder;

public class SnapshotFileFinder_ES_5_4 extends BaseSnapshotFileFinder {

    // ES 5.4 snapshot repo contains index-0, index-1, etc.
    private static final Pattern INDEX_PATTERN = Pattern.compile("^index-(\\d+)$");

    @Override
    public Pattern getSnapshotRepoDataIndexPattern() {
        return INDEX_PATTERN;
    }
}
