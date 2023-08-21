package org.opensearch.migrations.replay;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerAsyncClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class AWSAuthServiceTest {

    @Mock
    private SecretsManagerAsyncClient secretsManagerClient;

    @Test
    public void testBasicAuthHeaderFromSecret() throws ExecutionException, InterruptedException {
        String testSecretId = "testSecretId";
        String testUsername = "testAdmin";
        String expectedResult = "Basic dGVzdEFkbWluOmFkbWluUGFzcw==";

        GetSecretValueResponse response = GetSecretValueResponse.builder().secretString("adminPass").build();
        CompletableFuture<GetSecretValueResponse> responseFuture = CompletableFuture.completedFuture(response);

        when(secretsManagerClient.getSecretValue(any(Consumer.class))).thenReturn(responseFuture);

        AWSAuthService awsAuthService = new AWSAuthService(secretsManagerClient);
        String header = awsAuthService.getBasicAuthHeaderFromSecret(testUsername, testSecretId);
        Assertions.assertEquals(expectedResult, header);
    }


}
