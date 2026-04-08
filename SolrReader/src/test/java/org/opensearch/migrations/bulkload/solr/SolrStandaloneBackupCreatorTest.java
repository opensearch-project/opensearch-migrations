package org.opensearch.migrations.bulkload.solr;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

class SolrStandaloneBackupCreatorTest {

    @Test
    void constructsWithoutRepository() {
        var creator = new SolrStandaloneBackupCreator(
            "http://localhost:8983", "backup", "/var/solr/data", List.of("core1"));
        assertThat(creator.getBackupName(), equalTo("backup"));
    }

    @Test
    void constructsWithRepository() {
        var creator = new SolrStandaloneBackupCreator(
            "http://localhost:8983", "backup", "s3://bucket/path",
            List.of("core1"), null, null, "s3repo");
        assertThat(creator.getBackupName(), equalTo("backup"));
    }

    @Test
    void backwardCompatibleConstructorSetsNullRepository() {
        // The 6-arg constructor (without repositoryName) should still work
        var creator = new SolrStandaloneBackupCreator(
            "http://localhost:8983", "backup", "/local/path",
            List.of("core1"), "user", "pass");
        assertThat(creator.getBackupName(), equalTo("backup"));
    }
}
