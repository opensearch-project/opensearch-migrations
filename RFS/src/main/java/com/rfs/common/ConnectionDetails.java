package com.rfs.common;

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
            // Parse the URL to get the protocol, host name, and port
            String[] urlParts = url.split("://");
            if (urlParts.length != 2) {
                throw new IllegalArgumentException("Invalid URL format");
            }

            hostName = urlParts[1].split(":")[0];

            String[] portParts = urlParts[1].split(":");
            port = portParts.length == 1 ? -1 : Integer.parseInt(portParts[1].split("/")[0]);

            if (urlParts[0].equals("http")) {
                protocol = Protocol.HTTP;
            } else if (urlParts[0].equals("https")) {
                protocol = Protocol.HTTPS;
            } else {
                throw new IllegalArgumentException("Invalid protocol");
            }
        }        
    }
}
