package org.opensearch.migrations.bulkload.solr;

import java.util.List;

import org.opensearch.migrations.bulkload.common.http.ConnectionContext;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

class SolrStandaloneBackupCreatorTest {

    private static ConnectionContext noAuthContext(String url) {
        return new ConnectionContext.SourceArgs() {{ host = url; insecure = true; }}.toConnectionContext();
    }

    @Test
    void constructsWithoutRepository() {
        var creator = new SolrStandaloneBackupCreator(
            "http://localhost:8983", "backup", "/var/solr/data",
            List.of("core1"), noAuthContext("http://localhost:8983"));
        assertThat(creator.getBackupName(), equalTo("backup"));
    }

    @Test
    void constructsWithRepository() {
        var creator = new SolrStandaloneBackupCreator(
            "http://localhost:8983", "backup", "s3://bucket/path",
            List.of("core1"), noAuthContext("http://localhost:8983"), "s3repo");
        assertThat(creator.getBackupName(), equalTo("backup"));
    }
}
