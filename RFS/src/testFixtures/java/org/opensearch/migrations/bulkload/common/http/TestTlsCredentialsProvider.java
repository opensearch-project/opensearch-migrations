package org.opensearch.migrations.bulkload.common.http;

import java.io.InputStream;

public class TestTlsCredentialsProvider implements TlsCredentialsProvider {
    private final TestTlsUtils.CertificateBundle caCertBundle;
    private final TestTlsUtils.CertificateBundle clientCertBundle;

    public TestTlsCredentialsProvider(
            TestTlsUtils.CertificateBundle caCertBundle,
            TestTlsUtils.CertificateBundle clientCertBundle) {
        this.caCertBundle = caCertBundle;
        this.clientCertBundle = clientCertBundle;
    }

    @Override
    public InputStream getCaCertInputStream() {
        return caCertBundle != null ? caCertBundle.getCertificateInputStream() : null;
    }

    @Override
    public InputStream getClientCertInputStream() {
        return clientCertBundle != null ? clientCertBundle.getCertificateInputStream() : null;
    }

    @Override
    public InputStream getClientCertKeyInputStream() {
        return clientCertBundle != null ? clientCertBundle.getPrivateKeyInputStream() : null;
    }

    @Override
    public boolean hasClientCredentials() {
        return clientCertBundle != null;
    }

    @Override
    public boolean hasCACredentials() {
        return caCertBundle != null;
    }
}
