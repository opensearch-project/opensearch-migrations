package org.opensearch.migrations.aws;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.LongSupplier;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

/**
 * Static S3 keys read from a mounted Secret ({@code accessKey}/{@code secretKey} files) and re-read at most
 * once per reload interval, so an updated Secret takes effect without a restart. S3 clients (including the
 * CRT client) call {@link #resolveCredentials()} for every request, so the common path is a volatile read.
 *
 * <p>Kubelet updates a Secret volume by atomically repointing its {@code ..data} symlink, so both files are
 * read through the resolved {@code ..data} directory to avoid pairing an old access key with a new secret.
 * A failed or empty read keeps the previous keys rather than breaking in-flight work.
 */
@Slf4j
class ReloadingFileCredentialsProvider implements AwsCredentialsProvider {
    private static final String KUBELET_DATA_DIR = "..data";

    private final Path dir;
    private final long reloadIntervalNanos;
    private final LongSupplier nanoClock;
    private volatile AwsCredentials credentials;
    private volatile long nextCheckNanos;

    ReloadingFileCredentialsProvider(Path dir, Duration reloadInterval, LongSupplier nanoClock) {
        this.dir = dir;
        this.reloadIntervalNanos = reloadInterval.toNanos();
        this.nanoClock = nanoClock;
        try {
            this.credentials = read();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read S3 repo credentials from " + dir, e);
        }
        this.nextCheckNanos = nanoClock.getAsLong() + reloadIntervalNanos;
        log.atInfo().setMessage("Using S3 repo credentials from {}").addArgument(dir).log();
    }

    @Override
    public AwsCredentials resolveCredentials() {
        if (nanoClock.getAsLong() - nextCheckNanos >= 0) {
            reloadIfDue();
        }
        return credentials;
    }

    private synchronized void reloadIfDue() {
        var now = nanoClock.getAsLong();
        if (now - nextCheckNanos < 0) {
            return; // another thread reloaded while we waited for the lock
        }
        nextCheckNanos = now + reloadIntervalNanos;
        try {
            var latest = read();
            if (!latest.accessKeyId().equals(credentials.accessKeyId())
                || !latest.secretAccessKey().equals(credentials.secretAccessKey())) {
                credentials = latest;
                log.atInfo().setMessage("S3 repo credentials in {} changed; using the new keys").addArgument(dir).log();
            }
        } catch (IOException | IllegalStateException e) {
            log.atWarn().setCause(e)
                .setMessage("Could not reload S3 repo credentials from {}; keeping the previous keys")
                .addArgument(dir).log();
        }
    }

    private AwsCredentials read() throws IOException {
        var dataDir = dir.resolve(KUBELET_DATA_DIR);
        var base = Files.exists(dataDir) ? dataDir.toRealPath() : dir;
        var accessKey = Files.readString(base.resolve(S3RepoCredentials.ACCESS_KEY_FILE)).trim();
        var secretKey = Files.readString(base.resolve(S3RepoCredentials.SECRET_KEY_FILE)).trim();
        if (accessKey.isEmpty() || secretKey.isEmpty()) {
            throw new IllegalStateException("Empty " + S3RepoCredentials.ACCESS_KEY_FILE + " or "
                + S3RepoCredentials.SECRET_KEY_FILE + " in " + base);
        }
        return AwsBasicCredentials.create(accessKey, secretKey);
    }
}
