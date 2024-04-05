package com.rfs.common;

/**
 * Stores the connection details (assuming basic auth) for an Elasticsearch/OpenSearch cluster
 */
public class ConnectionDetails {
    public static enum AuthType {
        BASIC,
        NONE
    }

    public final String host;
    public final String username;
    public final String password;
    public final AuthType authType;

    public ConnectionDetails(String host, String username, String password) {
        this.host = host; // http://localhost:9200

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
    }
}
