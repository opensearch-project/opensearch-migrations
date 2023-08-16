package org.opensearch.migrations.replay;

import com.amazonaws.secretsmanager.caching.SecretCache;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.Charset;
import java.util.Base64;

@Slf4j
public class AWSAuthService implements AutoCloseable {

    private final SecretCache secretCache;

    public AWSAuthService(SecretCache secretCache) {
        this.secretCache = secretCache;
    }

    public AWSAuthService() {
        this(new SecretCache());
    }

    // SecretId here can be either the unique name of the secret or the secret ARN
    public String getSecret(String secretId) {
        return secretCache.getSecretString(secretId);
    }

    /**
     * This method returns a Basic Auth header string, with the username:password Base64 encoded
     * @param username The plaintext username
     * @param secretId The unique name of the secret or the secret ARN from AWS Secrets Manager. Its retrieved value
     *                 will fill the password part of the Basic Auth header
     * @return Basic Auth header string
     */
    public String getBasicAuthHeaderFromSecret(String username, String secretId) {
        String authHeaderString = username + ":" + getSecret(secretId);
        return "Basic " + Base64.getEncoder().encodeToString(authHeaderString.getBytes(Charset.defaultCharset()));
    }

    @Override
    public void close() {
        secretCache.close();
    }
}
