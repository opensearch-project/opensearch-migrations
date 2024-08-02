package com.rfs.common.http;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;

import com.beust.jcommander.Parameter;
import lombok.Getter;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

/**
 * Stores the connection context for an Elasticsearch/OpenSearch cluster
 */
@Getter
public class ConnectionContext {
    public enum Protocol {
        HTTP,
        HTTPS
    }

    private final URI uri;
    private final Protocol protocol;
    private final boolean insecure;
    private final RequestTransformer requestTransformer;

    private ConnectionContext(IParams params) {
        assert params.getHost() != null : "host is null";

        this.insecure = params.isInsecure();

        try {
            uri = new URI(params.getHost()); // e.g. http://localhost:9200
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URL format", e);
        }

        if (uri.getScheme().equals("http")) {
            protocol = Protocol.HTTP;
        } else if (uri.getScheme().equals("https")) {
            protocol = Protocol.HTTPS;
        } else {
            throw new IllegalArgumentException("Invalid protocol");
        }

        if (params.getUsername() != null ^ params.getPassword() != null) {
            throw new IllegalArgumentException("Both username and password must be provided, or neither");
        }
        if (params.getAwsRegion() != null ^ params.getAwsServiceSigningName() != null) {
            throw new IllegalArgumentException("Both aws region and aws service signing name must be provided, or neither");
        }

        var basicAuthEnabled = params.getUsername() != null && params.getPassword() != null;
        var sigv4Enabled = params.getAwsRegion() != null && params.getAwsServiceSigningName() != null;

        if (basicAuthEnabled && sigv4Enabled) {
            throw new IllegalArgumentException("Cannot have both Basic Auth and SigV4 Auth enabled.");
        }

        if (basicAuthEnabled) {
            this.requestTransformer = new BasicAuthTransformer(params.getUsername(), params.getPassword());
        }
        else if (sigv4Enabled) {
            this.requestTransformer = new SigV4AuthTransformer(
                DefaultCredentialsProvider.create(),
                params.getAwsServiceSigningName(),
                params.getAwsRegion(),
                protocol.name(),
                Clock::systemUTC);
        }
        else {
            this.requestTransformer = NoAuthTransformer.INSTANCE;
        }
    }

    public interface IParams {
        String getHost();

        String getUsername();

        String getPassword();

        String getAwsRegion();

        String getAwsServiceSigningName();

        boolean isInsecure();

        default ConnectionContext toConnectionContext() {
            return new ConnectionContext(this);
        }
    }

    @Getter
    public static class TargetArgs implements IParams {
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
            "--target-aws-region" }, description = "Optional. The target aws region. Required only if sigv4 auth is used", required = false)
        public String awsRegion = null;

        @Parameter(names = {
            "--target-aws-service-signing-name" }, description = "Optional. The target aws service signing name, e.g 'es' for Amazon OpenSearch Service and 'aoss' for Amazon OpenSearch Serverless. Required if sigv4 auth is used.", required = false)
        public String awsServiceSigningName = null;

        @Parameter(names = {
            "--target-insecure" }, description = "Allow untrusted SSL certificates for target", required = false)
        public boolean insecure = false;
    }

    @Getter
    public static class SourceArgs implements IParams {
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
            "--source-aws-region" }, description = "Optional. The source aws region, e.g. 'us-east-1'. Required if sigv4 auth is used", required = false)
        public String awsRegion = null;

        @Parameter(names = {
            "--source-aws-service-signing-name" }, description = "Optional. The source aws service signing name, e.g 'es' for Amazon OpenSearch Service and 'aoss' for Amazon OpenSearch Serverless. Required if sigv4 auth is used.", required = false)
        public String awsServiceSigningName = null;

        @Parameter(names = {
            "--source-insecure" }, description = "Allow untrusted SSL certificates for source", required = false)
        public boolean insecure = false;
    }
}
