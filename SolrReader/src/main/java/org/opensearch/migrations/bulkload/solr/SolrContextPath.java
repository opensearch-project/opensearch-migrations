package org.opensearch.migrations.bulkload.solr;

/**
 * Normalization for the Solr context path — the prefix Solr's APIs are served under. It is
 * configurable ({@code solr.contextPath}) and reverse proxies commonly rewrite it, so the stock
 * {@value #DEFAULT} cannot be assumed. An empty path means Solr is mounted at the host root.
 */
public final class SolrContextPath {

    public static final String DEFAULT = "/solr";

    private SolrContextPath() {}

    /**
     * Returns {@code ""} or a {@code /}-prefixed path with no trailing slash, so callers can build
     * URLs as {@code baseUrl + contextPath + "/..."}.
     *
     * @param contextPath the configured context path, or null for {@value #DEFAULT}
     * @throws IllegalArgumentException if the value is a URL or carries a query/fragment
     */
    public static String normalize(String contextPath) {
        if (contextPath == null) {
            return DEFAULT;
        }
        var path = contextPath.trim();
        if (path.contains("://") || path.contains("?") || path.contains("#")) {
            throw new IllegalArgumentException(
                "Solr context path must be a path such as '/solr' (or empty when Solr is served at the root), "
                    + "not a URL or query string: " + contextPath);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.isEmpty()) {
            return "";
        }
        return path.startsWith("/") ? path : "/" + path;
    }
}
