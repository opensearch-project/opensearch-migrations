package org.opensearch.migrations.replay;

import java.util.function.Consumer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@WrapWithNettyLeakDetection(disableLeakChecks = true)
public class AWSAuthServiceTest {

    @Mock
    private SecretsManagerClient secretsManagerClient;

    @Test
    public void testBasicAuthHeaderFromSecret() {
        String testSecretId = "testSecretId";
        String testUsername = "testAdmin";
        String expectedResult = "Basic dGVzdEFkbWluOmFkbWluUGFzcw==";

        GetSecretValueResponse response = GetSecretValueResponse.builder().secretString("adminPass").build();

        when(secretsManagerClient.getSecretValue(any(Consumer.class))).thenReturn(response);

        AWSAuthService awsAuthService = new AWSAuthService(secretsManagerClient);
        String header = awsAuthService.getBasicAuthHeaderFromSecret(testUsername, testSecretId);
        Assertions.assertEquals(expectedResult, header);
    }

}
