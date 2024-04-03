package com.rfs.common;

/**
 * Stores the connection details (assuming basic auth) for an Elasticsearch/OpenSearch cluster
 */
public class ConnectionDetails {
    public final String host;
    public final String username;
    public final String password;

    public ConnectionDetails(String host, String username, String password) {
        this.host = host; // http://localhost:9200
        this.username = username;
        this.password = password;
    }
}
