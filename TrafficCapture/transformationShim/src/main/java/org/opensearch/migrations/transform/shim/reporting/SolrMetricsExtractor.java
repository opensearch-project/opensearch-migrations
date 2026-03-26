package org.opensearch.migrations.transform.shim.reporting;

import java.net.URI;
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

    private static Matcher matchPath(String uri) {
        if (uri == null) return null;
        String path = URI.create(uri).getPath();
        Matcher m = COLLECTION_PATTERN.matcher(path);
        return m.matches() ? m : null;
    }

    @Override
    public String extractCollectionName(String uri) {
        Matcher m = matchPath(uri);
        return m != null ? m.group(1) : null;
    }

    @Override
    public String normalizeEndpoint(String uri) {
        Matcher m = matchPath(uri);
        return m != null ? "/solr/{collection}/" + m.group(2) : null;
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
