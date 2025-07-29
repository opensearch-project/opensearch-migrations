package org.opensearch.migrations.bulkload.version_es_7_10;

import java.util.regex.Pattern;

import org.opensearch.migrations.bulkload.common.BaseSnapshotFileFinder;

public class SnapshotFileFinder_ES_7_10 extends BaseSnapshotFileFinder {

    // Example match: index-0, index-42
    private static final Pattern INDEX_PATTERN = Pattern.compile("^index-(\\d+)$");

    @Override
    public Pattern getSnapshotRepoDataIndexPattern() {
        return INDEX_PATTERN;
    }
}
