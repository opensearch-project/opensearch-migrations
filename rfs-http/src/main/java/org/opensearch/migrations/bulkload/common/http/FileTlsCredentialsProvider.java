package org.opensearch.migrations.bulkload.common.http;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.file.Path;

public class FileTlsCredentialsProvider implements TlsCredentialsProvider {
    private final Path caCert;
    private final Path clientCert;
    private final Path clientCertKey;

    public FileTlsCredentialsProvider(Path caCert, Path clientCert, Path clientCertKey) {
        this.caCert = caCert;
        this.clientCert = clientCert;
        this.clientCertKey = clientCertKey;
    }

    public InputStream getCaCertInputStream() {
        return openStream(caCert);
    }

    public InputStream getClientCertInputStream() {
        return openStream(clientCert);
    }

    public InputStream getClientCertKeyInputStream() {
        return openStream(clientCertKey);
    }

    public boolean hasClientCredentials() {
        return clientCert != null && clientCertKey != null;
    }

    public boolean hasCACredentials() {
        return caCert != null;
    }

    private InputStream openStream(Path path) {
        try {
            return new FileInputStream(path.toFile());
        } catch (FileNotFoundException e) {
            throw new TlsCredentialLoadingException("Failed to load " + path, e);
        }
    }

    public static class TlsCredentialLoadingException extends RuntimeException {
        public TlsCredentialLoadingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
