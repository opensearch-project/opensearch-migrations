package org.opensearch.migrations.utils;

import java.net.URI;

/**
 * Utility methods for working with URIs in the context of cluster endpoints.
 */
public class URIHelper {

    private URIHelper() {}

    /**
     * Parses the given URI string and ensures an explicit port is present.
     * If no port is specified, the default port for the scheme is inferred:
     * 443 for https, 80 for http.
     *
     * @throws IllegalArgumentException if the URI is malformed, missing a host,
     *                                  missing a scheme, or uses an unsupported
     *                                  scheme with no explicit port.
     */
    public static URI parseUriWithDefaultPort(String uriString) {
        URI uri;
        try {
            uri = new URI(uriString);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid URI: " + uriString, e);
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("Hostname not present for URI: " + uri);
        }
        if (uri.getScheme() == null) {
            throw new IllegalArgumentException("Scheme (http|https) is not present for URI: " + uri);
        }
        if (uri.getPort() < 0) {
            int defaultPort;
            if ("https".equalsIgnoreCase(uri.getScheme())) {
                defaultPort = 443;
            } else if ("http".equalsIgnoreCase(uri.getScheme())) {
                defaultPort = 80;
            } else {
                throw new IllegalArgumentException("Port not present for URI: " + uri);
            }
            try {
                uri = new URI(
                    uri.getScheme(),
                    uri.getUserInfo(),
                    uri.getHost(),
                    defaultPort,
                    uri.getPath(),
                    uri.getQuery(),
                    uri.getFragment()
                );
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to construct URI with default port for: " + uri, e);
            }
        }
        return uri;
    }
}
