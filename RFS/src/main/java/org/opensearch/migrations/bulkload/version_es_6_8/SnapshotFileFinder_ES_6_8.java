package org.opensearch.migrations.bulkload.version_es_6_8;

import java.util.regex.Pattern;

import org.opensearch.migrations.bulkload.common.BaseSnapshotFileFinder;

public class SnapshotFileFinder_ES_6_8 extends BaseSnapshotFileFinder {

    // ES 6.8 uses index-N just like ES 7.10
    private static final Pattern INDEX_PATTERN = Pattern.compile("^index-(\\d+)$");

    @Override
    public Pattern getSnapshotRepoDataIndexPattern() {
        return INDEX_PATTERN;
    }
}
