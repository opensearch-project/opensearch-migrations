package org.opensearch.migrations.replay;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerAsyncClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.nio.charset.Charset;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

@Slf4j
public class AWSAuthService implements AutoCloseable {

    private final SecretsManagerAsyncClient secretsManagerClient;

    public AWSAuthService(SecretsManagerAsyncClient secretsManagerClient) {
        this.secretsManagerClient = secretsManagerClient;
    }

    public AWSAuthService() {
        this(SecretsManagerAsyncClient.builder().build());
    }

    // SecretId here can be either the unique name of the secret or the secret ARN
    public CompletableFuture<GetSecretValueResponse> getSecret(String secretId) {
        return secretsManagerClient.getSecretValue(builder -> builder.secretId(secretId));
    }

    /**
     * This method synchronously returns a Basic Auth header string, with the username:password Base64 encoded
     * @param username The plaintext username
     * @param secretId The unique name of the secret or the secret ARN from AWS Secrets Manager. Its retrieved value
     *                 will fill the password part of the Basic Auth header
     * @return Basic Auth header string
     */
    public String getBasicAuthHeaderFromSecret(String username, String secretId) throws ExecutionException, InterruptedException {
        String secretValue = getSecret(secretId).get().secretString();
        String authHeaderString = username + ":" + secretValue;
        return "Basic " + Base64.getEncoder().encodeToString(authHeaderString.getBytes(Charset.defaultCharset()));
    }

    @Override
    public void close() {
        secretsManagerClient.close();
    }
}