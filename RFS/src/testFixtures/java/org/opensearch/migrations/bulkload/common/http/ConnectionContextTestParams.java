package org.opensearch.migrations.bulkload.common.http;

import java.nio.file.Path;

import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class ConnectionContextTestParams implements ConnectionContext.IParams {
    private String host;
    private String username;
    private String password;
    private String awsRegion;
    private String awsServiceSigningName;
    @Builder.Default
    private boolean insecure = true;
    private boolean compressionEnabled;

    private Path caCert;
    private Path clientCert;
    private Path clientCertKey;
}
