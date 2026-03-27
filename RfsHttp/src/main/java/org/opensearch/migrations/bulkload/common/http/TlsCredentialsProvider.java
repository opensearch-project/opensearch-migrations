package org.opensearch.migrations.bulkload.common.http;

import java.io.InputStream;

public interface TlsCredentialsProvider {
    InputStream getCaCertInputStream();
    InputStream getClientCertInputStream();
    InputStream getClientCertKeyInputStream();
    boolean hasClientCredentials();
    boolean hasCACredentials();
}
