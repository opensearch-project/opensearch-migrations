package org.opensearch.migrations.replay;

import java.nio.charset.Charset;
import java.util.Base64;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

@Slf4j
public class AWSAuthService implements AutoCloseable {

    private final SecretsManagerClient secretsManagerClient;

    public AWSAuthService(SecretsManagerClient secretsManagerClient) {
        this.secretsManagerClient = secretsManagerClient;
    }

    public AWSAuthService(AwsCredentialsProvider credentialsProvider, Region region) {
        this(SecretsManagerClient.builder().credentialsProvider(credentialsProvider).region(region).build());
    }

    // SecretId here can be either the unique name of the secret or the secret ARN
    public String getSecret(String secretId) {
        return secretsManagerClient.getSecretValue(builder -> builder.secretId(secretId)).secretString();
    }

    /**
     * This method synchronously returns a Basic Auth header string, with the username:password Base64 encoded
     * @param username The plaintext username
     * @param secretId The unique name of the secret or the secret ARN from AWS Secrets Manager. Its retrieved value
     *                 will fill the password part of the Basic Auth header
     * @return Basic Auth header string
     */
    public String getBasicAuthHeaderFromSecret(String username, String secretId) {
        String secretValue = getSecret(secretId);
        String authHeaderString = username + ":" + secretValue;
        return "Basic " + Base64.getEncoder().encodeToString(authHeaderString.getBytes(Charset.defaultCharset()));
    }

    @Override
    public void close() {
        secretsManagerClient.close();
    }
}
