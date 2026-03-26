package org.opensearch.migrations.transform.shim.reporting;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Solr-specific implementation of {@link MetricsExtractor}.
 * Parses URIs of the form /solr/{collection}/{handler} and uses
 * Solr response paths (response.numFound, responseHeader.QTime).
 */
public final class SolrMetricsExtractor implements MetricsExtractor {

    private static final Pattern COLLECTION_PATTERN = Pattern.compile("^/solr/([^/]+)/(.+)$");

    @Override
    public String extractCollectionName(String uri) {
        if (uri == null) return null;
        String path = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
        Matcher m = COLLECTION_PATTERN.matcher(path);
        return m.matches() ? m.group(1) : null;
    }

    @Override
    public String normalizeEndpoint(String uri) {
        if (uri == null) return null;
        String path = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
        Matcher m = COLLECTION_PATTERN.matcher(path);
        if (!m.matches()) return null;
        return "/solr/{collection}/" + m.group(2);
    }

    @Override
    public String hitCountPath() {
        return "response.numFound";
    }

    @Override
    public String queryTimePath() {
        return "responseHeader.QTime";
    }

    @Override
    public List<ValidationDocument.ComparisonEntry> compareResults(
            Map<String, Object> baselineBody, Map<String, Object> candidateBody) {
        return FacetComparator.compareFacets(baselineBody, candidateBody);
    }
}
