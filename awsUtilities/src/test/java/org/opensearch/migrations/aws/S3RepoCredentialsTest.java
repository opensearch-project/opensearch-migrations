package org.opensearch.migrations.aws;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3RepoCredentialsTest {
    @TempDir
    Path dir;

    private static Map<String, String> envWithDir(String value) {
        return Map.of(S3RepoCredentials.CREDENTIALS_DIR_ENV_VAR, value);
    }

    @Test
    void usesDefaultChainWhenEnvVarIsUnset() {
        assertInstanceOf(DefaultCredentialsProvider.class, S3RepoCredentials.provider(Map.<String, String>of()::get));
    }

    @Test
    void usesDefaultChainWhenEnvVarIsBlank() {
        assertInstanceOf(DefaultCredentialsProvider.class, S3RepoCredentials.provider(envWithDir("  ")::get));
    }

    @Test
    void usesDefaultChainWhenMountedDirIsEmpty() {
        assertInstanceOf(DefaultCredentialsProvider.class,
            S3RepoCredentials.provider(envWithDir(dir.toString())::get));
    }

    @Test
    void usesMountedKeysWhenBothFilesExist() throws IOException {
        Files.writeString(dir.resolve(S3RepoCredentials.ACCESS_KEY_FILE), "AKIA1");
        Files.writeString(dir.resolve(S3RepoCredentials.SECRET_KEY_FILE), "secret1");

        var provider = S3RepoCredentials.provider(envWithDir(dir.toString())::get);

        assertInstanceOf(ReloadingFileCredentialsProvider.class, provider);
        var creds = provider.resolveCredentials();
        assertEquals("AKIA1", creds.accessKeyId());
        assertEquals("secret1", creds.secretAccessKey());
    }

    @Test
    void failsWhenOnlyAccessKeyExists() throws IOException {
        Files.writeString(dir.resolve(S3RepoCredentials.ACCESS_KEY_FILE), "AKIA1");
        var env = envWithDir(dir.toString());
        var e = assertThrows(IllegalStateException.class, () -> S3RepoCredentials.provider(env::get));
        assertTrue(e.getMessage().contains("s3CredentialsSecretName"));
    }

    @Test
    void failsWhenOnlySecretKeyExists() throws IOException {
        Files.writeString(dir.resolve(S3RepoCredentials.SECRET_KEY_FILE), "secret1");
        var env = envWithDir(dir.toString());
        assertThrows(IllegalStateException.class, () -> S3RepoCredentials.provider(env::get));
    }
}
