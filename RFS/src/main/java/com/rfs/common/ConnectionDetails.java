package com.rfs.common;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Stores the connection details (assuming basic auth) for an Elasticsearch/OpenSearch cluster
 */
public class ConnectionDetails {
    public static enum AuthType {
        BASIC,
        NONE
    }

    public static enum Protocol {
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

    public ConnectionDetails(String url, String username, String password) {
        this.url = url; // http://localhost:9200

        // If the username is provided, the password must be as well, and vice versa
        if ((username == null && password != null) || (username != null && password == null)) {
            throw new IllegalArgumentException("Both username and password must be provided, or neither");
        } else if (username != null){
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
}
