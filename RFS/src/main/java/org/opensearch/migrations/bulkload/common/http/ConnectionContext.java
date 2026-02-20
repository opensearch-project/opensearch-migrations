package org.opensearch.migrations.bulkload.common.http;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

import org.opensearch.migrations.arguments.ArgNameConstants;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import com.beust.jcommander.converters.PathConverter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

/**
 * Stores the connection context for an Elasticsearch/OpenSearch cluster
 */
@Getter
@EqualsAndHashCode(exclude={"requestTransformer"})
@ToString(exclude={"requestTransformer"})
public class ConnectionContext {
    public enum Protocol {
        HTTP,
        HTTPS
    }

    @JsonProperty("uri")
    private final URI uri;
    @JsonProperty("protocol")
    private final Protocol protocol;
    @JsonProperty("insecure")
    private final boolean insecure;
    @JsonIgnore
    private final RequestTransformer requestTransformer;
    @JsonProperty("awsSpecificAuthentication")
    private final boolean awsSpecificAuthentication;
    @JsonProperty("disableCompression")
    private final boolean disableCompression;

    @JsonIgnore
    private TlsCredentialsProvider tlsCredentialsProvider;

    private ConnectionContext(IParams params) {
        if (params.getHost() == null) {
            throw new IllegalArgumentException("No host was found");
        }

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
        awsSpecificAuthentication = sigv4Enabled;

        if (basicAuthEnabled) {
            requestTransformer = new BasicAuthTransformer(params.getUsername(), params.getPassword());
        }
        else if (sigv4Enabled) {
            requestTransformer = new SigV4AuthTransformer(
                DefaultCredentialsProvider.builder().build(),
                params.getAwsServiceSigningName(),
                params.getAwsRegion(),
                protocol.name(),
                Clock::systemUTC);
        }
        else {
            requestTransformer = new NoAuthTransformer();
        }

        validateClientCertPairPresence(params);

        if (isTlsCredentialsEnabled(params)) {
            tlsCredentialsProvider = new FileTlsCredentialsProvider(
                params.getCaCert(),
                params.getClientCert(),
                params.getClientCertKey());
        }

        this.disableCompression = params.isDisableCompression();
    }

    // Used for presentation to user facing output
    public Map<String, String> toUserFacingData() {
        var dataBuilder = new LinkedHashMap<String, String>();
        dataBuilder.put("Uri", getUri().toString());
        dataBuilder.put("Protocol", getProtocol().toString());
        dataBuilder.put("TLS Verification", isInsecure() ? "Disabled" : "Enabled");
        if (awsSpecificAuthentication) {
            dataBuilder.put("AWS Auth", "Enabled");
        }
        return dataBuilder;
    }

    /**
     * Sets the TLS credentials provider.
     * NOTE: This method is only intended for testing purposes.
     */
    public void setTlsCredentialsProvider(TlsCredentialsProvider tlsCredentialsProvider) {
        this.tlsCredentialsProvider = tlsCredentialsProvider;
    }

    public interface IParams {
        String getHost();

        String getUsername();

        String getPassword();

        String getAwsRegion();

        String getAwsServiceSigningName();

        Path getCaCert();

        Path getClientCert();

        Path getClientCertKey();

        boolean isDisableCompression();

        boolean isInsecure();

        default ConnectionContext toConnectionContext() {
            return new ConnectionContext(this);
        }
    }

    @Getter
    public static class TargetArgs implements IParams {
        @Parameter(
            names = {"--target-host", "--targetHost" },
            description = "The target host and port (e.g. http://localhost:9200)",
            required = true)
        public String host;

        @Parameter(
            names = {ArgNameConstants.TARGET_USERNAME_ARG_CAMEL_CASE, ArgNameConstants.TARGET_USERNAME_ARG_KEBAB_CASE },
            description = "Optional.  The target username; if not provided, will assume no auth on target",
            required = false)
        public String username = null;

        @Parameter(
            names = {ArgNameConstants.TARGET_PASSWORD_ARG_CAMEL_CASE, ArgNameConstants.TARGET_PASSWORD_ARG_KEBAB_CASE },
            description = "Optional.  The target password; if not provided, will assume no auth on target",
            required = false)
        public String password = null;

        @Parameter(
            names = {"--target-cacert", "--targetCaCert" },
            description = "Optional. The target CA certificate",
            required = false,
            converter = PathConverter.class)
        public Path caCert = null;

        @Parameter(
            names = {"--target-client-cert", "--targetClientCert" },
            description = "Optional. The target client TLS certificate",
            required = false,
            converter = PathConverter.class)
        public Path clientCert = null;

        @Parameter(
            names = {"--target-client-cert-key", "--targetClientCertKey" },
            description = "Optional. The target client TLS certificate key",
            required = false,
            converter = PathConverter.class)
        public Path clientCertKey = null;

        @Parameter(
            names = {"--target-aws-region", "--targetAwsRegion" },
            description = "Optional. The target aws region. Required only if sigv4 auth is used",
            required = false)
        public String awsRegion = null;

        @Parameter(
            names = {"--target-aws-service-signing-name", "--targetAwsServiceSigningName" },
            description = "Optional. The target aws service signing name, e.g 'es' for " +
                "Amazon OpenSearch Service and 'aoss' for Amazon OpenSearch Serverless. " +
                "Required if sigv4 auth is used.",
            required = false)
        public String awsServiceSigningName = null;

        @Parameter(
            names = { "--target-insecure", "--targetInsecure" },
            description = "Allow untrusted SSL certificates for target", required = false)
        public boolean insecure = false;

        @ParametersDelegate
        TargetAdvancedArgs advancedArgs = new TargetAdvancedArgs();

        @Override
        public boolean isDisableCompression() {
            return advancedArgs.isDisableCompression();
        }
    }

    // Flags that require more testing and validation before recommendations are made
    @Getter
    public static class TargetAdvancedArgs {
        @Parameter(names = {"--disable-compression", "--disableCompression" },
            description = "**Advanced**. Disable request body compression even if supported on the target cluster."
        )
        public boolean isDisableCompression = false;
    }

    @Getter
    public static class SourceArgs implements IParams {
        @Parameter(
            names = {"--source-host", "--sourceHost" },
            description = "The source host and port (e.g. http://localhost:9200)",
            required = false)
        public String host = null;

        @Parameter(
            names = {ArgNameConstants.SOURCE_USERNAME_ARG_CAMEL_CASE, ArgNameConstants.SOURCE_USERNAME_ARG_KEBAB_CASE },
            description = "The source username; if not provided, will assume no auth on source",
            required = false)
        public String username = null;

        @Parameter(
            names = {ArgNameConstants.SOURCE_PASSWORD_ARG_CAMEL_CASE, ArgNameConstants.SOURCE_PASSWORD_ARG_KEBAB_CASE },
            description = "The source password; if not provided, will assume no auth on source",
            required = false)
        public String password = null;

        @Parameter(
            names = {"--source-cacert", "--sourceCaCert" },
            description = "Optional. The source CA certificate",
            required = false,
            converter = PathConverter.class)
        public Path caCert = null;

        @Parameter(
            names = {"--source-client-cert", "--sourceClientCert" },
            description = "Optional. The source client TLS certificate",
            required = false,
            converter = PathConverter.class)
        public Path clientCert = null;

        @Parameter(
            names = {"--source-client-cert-key", "--sourceClientCertKey" },
            description = "Optional. The source client TLS certificate key",
            required = false,
            converter = PathConverter.class)
        public Path clientCertKey = null;

        @Parameter(
            names = {"--source-aws-region", "--sourceAwsRegion" },
            description = "Optional. The source aws region, e.g. 'us-east-1'. Required if sigv4 auth is used",
            required = false)
        public String awsRegion = null;

        @Parameter(
            names = {"--source-aws-service-signing-name", "--sourceAwsServiceSigningName" },
            description = "Optional. The source aws service signing name, e.g 'es' for " +
                "Amazon OpenSearch Service and 'aoss' for Amazon OpenSearch Serverless. " +
                "Required if sigv4 auth is used.",
            required = false)
        public String awsServiceSigningName = null;

        @Parameter(
            names = {"--source-insecure", "--sourceInsecure" },
            description = "Allow untrusted SSL certificates for source",
            required = false)
        public boolean insecure = false;

        @Override
        public boolean isDisableCompression() {
            // No need to interrogate source cluster for compression information
             return true;
        }
    }

    @Getter
    public static class CoordinatorArgs implements IParams {
        @Parameter(
            names = {"--coordinator-host", "--coordinatorHost" },
            description = "Optional. The coordinator cluster host and port (e.g. http://localhost:9200). " +
                "When provided, work coordination will use this cluster instead of the target cluster.",
            required = false)
        public String host = null;

        @Parameter(
            names = {"--coordinator-username", "--coordinatorUsername" },
            description = "Optional. The coordinator cluster username; if not provided, will assume no auth on coordinator",
            required = false)
        public String username = null;

        @Parameter(
            names = {"--coordinator-password", "--coordinatorPassword" },
            description = "Optional. The coordinator cluster password; if not provided, will assume no auth on coordinator",
            required = false)
        public String password = null;

        @Parameter(
            names = {"--coordinator-cacert", "--coordinatorCaCert" },
            description = "Optional. The coordinator CA certificate",
            required = false,
            converter = PathConverter.class)
        public Path caCert = null;

        @Parameter(
            names = {"--coordinator-client-cert", "--coordinatorClientCert" },
            description = "Optional. The coordinator client TLS certificate",
            required = false,
            converter = PathConverter.class)
        public Path clientCert = null;

        @Parameter(
            names = {"--coordinator-client-cert-key", "--coordinatorClientCertKey" },
            description = "Optional. The coordinator client TLS certificate key",
            required = false,
            converter = PathConverter.class)
        public Path clientCertKey = null;

        @Parameter(
            names = {"--coordinator-aws-region", "--coordinatorAwsRegion" },
            description = "Optional. The coordinator aws region. Required only if sigv4 auth is used for coordinator",
            required = false)
        public String awsRegion = null;

        @Parameter(
            names = {"--coordinator-aws-service-signing-name", "--coordinatorAwsServiceSigningName" },
            description = "Optional. The coordinator aws service signing name, e.g 'es' for " +
                "Amazon OpenSearch Service and 'aoss' for Amazon OpenSearch Serverless. " +
                "Required if sigv4 auth is used for coordinator.",
            required = false)
        public String awsServiceSigningName = null;

        @Parameter(
            names = { "--coordinator-insecure", "--coordinatorInsecure" },
            description = "Allow untrusted SSL certificates for coordinator cluster",
            required = false)
        public boolean insecure = false;

        @Override
        public boolean isDisableCompression() {
            // Coordinator requests are lightweight, no need for compression
            return true;
        }

        /**
         * Returns true if coordinator arguments have been provided.
         * When host is provided, coordinator connection is considered enabled.
         */
        public boolean isEnabled() {
            return host != null;
        }
    }

    private static void validateClientCertPairPresence(IParams params) {
        if ((params.getClientCert() != null) ^ (params.getClientCertKey() != null)) {
            throw new IllegalArgumentException(
                    "Both clientCert and clientCertKey must be provided together, or neither.");
        }
    }

    private static boolean isTlsCredentialsEnabled(IParams params) {
        return (params.getCaCert() != null)  || (params.getClientCert() != null && params.getClientCertKey() != null);
    }
}
