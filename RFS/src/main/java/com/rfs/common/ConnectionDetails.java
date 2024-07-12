package com.rfs.common;

import java.net.URI;
import java.net.URISyntaxException;

import com.beust.jcommander.Parameter;
import lombok.Getter;

/**
 * Stores the connection details (assuming basic auth) for an Elasticsearch/OpenSearch cluster
 */
public class ConnectionDetails {
    public enum AuthType {
        BASIC,
        NONE
    }

    public enum Protocol {
        HTTP,
        HTTPS
    }

    public final String url;
    public final Protocol protocol;
    public final String hostName;
    public final int port;
    public final String username;
    public final String password;
    public final AuthType authType;
    public final boolean insecure;

    public ConnectionDetails(Params params) {
        this(params.getHost(), params.getUsername(), params.getPassword(), params.isInsecure());
    }

    public ConnectionDetails(String url, String username, String password) {
        this(url, username, password, false);
    }

    public ConnectionDetails(String url, String username, String password, boolean insecure) {
        this.url = url; // http://localhost:9200
        this.insecure = insecure;

        // If the username is provided, the password must be as well, and vice versa
        if ((username == null && password != null) || (username != null && password == null)) {
            throw new IllegalArgumentException("Both username and password must be provided, or neither");
        } else if (username != null) {
            this.authType = AuthType.BASIC;
        } else {
            this.authType = AuthType.NONE;
        }

        this.username = username;
        this.password = password;

        if (url == null) {
            hostName = null;
            port = -1;
            protocol = null;
        } else {
            URI uri;
            try {
                uri = new URI(url);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("Invalid URL format", e);
            }

            hostName = uri.getHost();
            port = uri.getPort(); // Default port can be set here if -1

            if (uri.getScheme().equals("http")) {
                protocol = Protocol.HTTP;
            } else if (uri.getScheme().equals("https")) {
                protocol = Protocol.HTTPS;
            } else {
                throw new IllegalArgumentException("Invalid protocol");
            }
        }
    }

    public static interface Params {
        String getHost();

        String getUsername();

        String getPassword();

        boolean isInsecure();
    }

    @Getter
    public static class TargetArgs implements Params {
        @Parameter(names = {
            "--target-host" }, description = "The target host and port (e.g. http://localhost:9200)", required = true)
        public String host;

        @Parameter(names = {
            "--target-username" }, description = "Optional.  The target username; if not provided, will assume no auth on target", required = false)
        public String username = null;

        @Parameter(names = {
            "--target-password" }, description = "Optional.  The target password; if not provided, will assume no auth on target", required = false)
        public String password = null;

        @Parameter(names = {
            "--target-insecure" }, description = "Allow untrusted SSL certificates for target", required = false)
        public boolean insecure = false;
    }

    @Getter
    public static class SourceArgs implements Params {
        @Parameter(names = {
            "--source-host" }, description = "The source host and port (e.g. http://localhost:9200)", required = false)
        public String host = null;

        @Parameter(names = {
            "--source-username" }, description = "The source username; if not provided, will assume no auth on source", required = false)
        public String username = null;

        @Parameter(names = {
            "--source-password" }, description = "The source password; if not provided, will assume no auth on source", required = false)
        public String password = null;

        @Parameter(names = {
            "--source-insecure" }, description = "Allow untrusted SSL certificates for source", required = false)
        public boolean insecure = false;
    }
}
