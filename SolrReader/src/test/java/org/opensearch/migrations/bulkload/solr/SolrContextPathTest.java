package org.opensearch.migrations.bulkload.solr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SolrContextPathTest {

    @Test
    void nullYieldsStockDefault() {
        assertEquals("/solr", SolrContextPath.normalize(null));
        assertEquals("/solr", SolrContextPath.DEFAULT);
    }

    @Test
    void emptyAndSlashOnlyMeanHostRoot() {
        assertEquals("", SolrContextPath.normalize(""));
        assertEquals("", SolrContextPath.normalize("/"));
        assertEquals("", SolrContextPath.normalize("///"));
        assertEquals("", SolrContextPath.normalize("  "));
    }

    @Test
    void leadingSlashIsAdded() {
        assertEquals("/solr", SolrContextPath.normalize("solr"));
        assertEquals("/tenant-a/solr", SolrContextPath.normalize("tenant-a/solr"));
    }

    @Test
    void trailingSlashesAreStripped() {
        assertEquals("/solr", SolrContextPath.normalize("/solr/"));
        assertEquals("/solr", SolrContextPath.normalize("/solr//"));
        assertEquals("/tenant-a/solr", SolrContextPath.normalize("tenant-a/solr/"));
    }

    @Test
    void surroundingWhitespaceIsIgnored() {
        assertEquals("/solr", SolrContextPath.normalize("  /solr  "));
    }

    @Test
    void nestedPathIsPreserved() {
        assertEquals("/a/b/c", SolrContextPath.normalize("/a/b/c"));
    }

    @Test
    void alreadyNormalizedValuesAreUnchanged() {
        assertEquals("/solr", SolrContextPath.normalize("/solr"));
        assertEquals("", SolrContextPath.normalize(""));
    }

    @Test
    void urlOrQueryStringIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> SolrContextPath.normalize("http://localhost:8983/solr"));
        assertThrows(IllegalArgumentException.class,
            () -> SolrContextPath.normalize("/solr?wt=json"));
        assertThrows(IllegalArgumentException.class,
            () -> SolrContextPath.normalize("/solr#frag"));
    }
}
