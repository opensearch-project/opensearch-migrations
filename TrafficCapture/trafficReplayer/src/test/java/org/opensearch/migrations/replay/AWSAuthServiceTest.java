package org.opensearch.migrations.replay;

import com.amazonaws.secretsmanager.caching.SecretCache;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class AWSAuthServiceTest {

    @Mock
    private SecretCache secretCache;

    @Test
    public void testBasicAuthHeaderFromSecret() {
        String testSecretId = "testSecretId";
        String testUsername = "testAdmin";
        String expectedResult = "Basic dGVzdEFkbWluOmFkbWluUGFzcw==";

        when(secretCache.getSecretString(testSecretId)).thenReturn("adminPass");

        AWSAuthService awsAuthService = new AWSAuthService(secretCache);
        String header = awsAuthService.getBasicAuthHeaderFromSecret(testUsername, testSecretId);
        Assertions.assertEquals(expectedResult, header);
    }


}
