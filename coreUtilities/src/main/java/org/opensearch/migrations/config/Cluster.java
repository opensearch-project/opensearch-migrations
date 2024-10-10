package org.opensearch.migrations.config;

public class Cluster {
    public String endpoint;
    public boolean allow_insecure;
    public String version;
    public NoAuth no_auth;
    public BasicAuth basic_auth;
    public Sigv4Auth sigv4;
}
