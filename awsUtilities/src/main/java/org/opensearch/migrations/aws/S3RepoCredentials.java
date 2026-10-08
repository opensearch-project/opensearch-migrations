package org.opensearch.migrations.aws;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.UnaryOperator;

import lombok.experimental.UtilityClass;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

/**
 * Credentials for S3 clients that touch a snapshot repository. The migration workflow mounts the repo's
 * {@code s3CredentialsSecretName} Secret as files and points {@value #CREDENTIALS_DIR_ENV_VAR} at the mount,
 * rather than exporting {@code AWS_ACCESS_KEY_ID}/{@code AWS_SECRET_ACCESS_KEY}: the standard env vars are
 * pod-wide and read first by {@link DefaultCredentialsProvider}, so they would also be used to SigV4-sign
 * source, target and coordinator cluster requests. Only S3 clients consult this; everything else keeps the
 * pod's identity. Because the files are re-read while running, updating the Secret rotates the keys without
 * restarting the pod.
 */
@UtilityClass
public class S3RepoCredentials {
    public static final String CREDENTIALS_DIR_ENV_VAR = "S3_REPO_CREDENTIALS_DIR";
    static final String ACCESS_KEY_FILE = "accessKey";
    static final String SECRET_KEY_FILE = "secretKey";
    /** Kubelet refreshes mounted Secrets within about a minute; this bounds the extra delay on our side. */
    static final Duration RELOAD_INTERVAL = Duration.ofSeconds(30);

    /**
     * @return a provider that reloads the mounted keys when both key files are present, otherwise the
     *         default provider chain (Pod Identity, IRSA, instance profile, ...)
     */
    public static AwsCredentialsProvider provider() {
        return provider(System::getenv);
    }

    /** Same as {@link #provider()}, reading the environment through {@code env}. */
    public static AwsCredentialsProvider provider(UnaryOperator<String> env) {
        var dirValue = env.apply(CREDENTIALS_DIR_ENV_VAR);
        if (dirValue == null || dirValue.isBlank()) {
            return DefaultCredentialsProvider.builder().build();
        }
        // With no Secret configured the workflow still mounts an (empty) optional volume here.
        var dir = Path.of(dirValue);
        var hasAccessKey = Files.exists(dir.resolve(ACCESS_KEY_FILE));
        var hasSecretKey = Files.exists(dir.resolve(SECRET_KEY_FILE));
        if (hasAccessKey != hasSecretKey) {
            throw new IllegalStateException("Both " + ACCESS_KEY_FILE + " and " + SECRET_KEY_FILE + " must exist in "
                + dir + ", or neither (check the repo's s3CredentialsSecretName Secret for both keys)");
        }
        if (!hasAccessKey) {
            return DefaultCredentialsProvider.builder().build();
        }
        return new ReloadingFileCredentialsProvider(dir, RELOAD_INTERVAL, System::nanoTime);
    }
}
