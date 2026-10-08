package org.opensearch.migrations.aws;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReloadingFileCredentialsProviderTest {
    private static final Duration INTERVAL = Duration.ofSeconds(30);

    @TempDir
    Path dir;

    private final AtomicLong clock = new AtomicLong(0);

    private ReloadingFileCredentialsProvider newProvider() {
        return new ReloadingFileCredentialsProvider(dir, INTERVAL, clock::get);
    }

    private void writeKeys(Path base, String accessKey, String secretKey) throws IOException {
        Files.writeString(base.resolve(S3RepoCredentials.ACCESS_KEY_FILE), accessKey);
        Files.writeString(base.resolve(S3RepoCredentials.SECRET_KEY_FILE), secretKey);
    }

    private void advance(Duration d) {
        clock.addAndGet(d.toNanos());
    }

    private void assertKeys(ReloadingFileCredentialsProvider provider, String accessKey, String secretKey) {
        var creds = provider.resolveCredentials();
        assertEquals(accessKey, creds.accessKeyId());
        assertEquals(secretKey, creds.secretAccessKey());
    }

    @Test
    void readsAndTrimsKeysAtConstruction() throws IOException {
        writeKeys(dir, "  AKIA1\n", "secret1\n");
        assertKeys(newProvider(), "AKIA1", "secret1");
    }

    @Test
    void constructionFailsWhenFilesAreMissing() {
        assertThrows(UncheckedIOException.class, this::newProvider);
    }

    @Test
    void constructionFailsWhenAKeyIsEmpty() throws IOException {
        writeKeys(dir, "AKIA1", "  \n");
        assertThrows(IllegalStateException.class, this::newProvider);
    }

    @Test
    void doesNotReloadBeforeIntervalElapses() throws IOException {
        writeKeys(dir, "AKIA1", "secret1");
        var provider = newProvider();
        writeKeys(dir, "AKIA2", "secret2");

        advance(INTERVAL.minusNanos(1));
        assertKeys(provider, "AKIA1", "secret1");
    }

    @Test
    void reloadsChangedKeysOnceIntervalElapses() throws IOException {
        writeKeys(dir, "AKIA1", "secret1");
        var provider = newProvider();
        writeKeys(dir, "AKIA2", "secret2");

        advance(INTERVAL);
        assertKeys(provider, "AKIA2", "secret2");
    }

    @Test
    void nextReloadIsScheduledFromLastCheck() throws IOException {
        writeKeys(dir, "AKIA1", "secret1");
        var provider = newProvider();

        advance(INTERVAL);
        assertKeys(provider, "AKIA1", "secret1"); // check happens, nothing changed
        writeKeys(dir, "AKIA2", "secret2");

        advance(INTERVAL.minusNanos(1));
        assertKeys(provider, "AKIA1", "secret1");
        advance(Duration.ofNanos(1));
        assertKeys(provider, "AKIA2", "secret2");
    }

    @Test
    void keepsPreviousKeysWhenFilesDisappear() throws IOException {
        writeKeys(dir, "AKIA1", "secret1");
        var provider = newProvider();
        Files.delete(dir.resolve(S3RepoCredentials.ACCESS_KEY_FILE));

        advance(INTERVAL);
        assertKeys(provider, "AKIA1", "secret1");
    }

    @Test
    void keepsPreviousKeysWhenAFileIsEmptied() throws IOException {
        writeKeys(dir, "AKIA1", "secret1");
        var provider = newProvider();
        writeKeys(dir, "AKIA2", "");

        advance(INTERVAL);
        assertKeys(provider, "AKIA1", "secret1");
    }

    @Test
    void recoversAfterAFailedReload() throws IOException {
        writeKeys(dir, "AKIA1", "secret1");
        var provider = newProvider();
        Files.delete(dir.resolve(S3RepoCredentials.SECRET_KEY_FILE));
        advance(INTERVAL);
        assertKeys(provider, "AKIA1", "secret1");

        writeKeys(dir, "AKIA2", "secret2");
        advance(INTERVAL);
        assertKeys(provider, "AKIA2", "secret2");
    }

    @Test
    void handlesNanoClockWraparound() throws IOException {
        clock.set(Long.MAX_VALUE - INTERVAL.toNanos() / 2);
        writeKeys(dir, "AKIA1", "secret1");
        var provider = newProvider(); // next check overflows to a negative value
        writeKeys(dir, "AKIA2", "secret2");

        assertKeys(provider, "AKIA1", "secret1");
        advance(INTERVAL);
        assertKeys(provider, "AKIA2", "secret2");
    }

    /**
     * Mimics a kubelet Secret volume: real files live in a timestamped directory, {@code ..data} points at
     * it, and the top-level key files are symlinks through {@code ..data}. Updates swap {@code ..data}.
     */
    @Test
    void readsThroughKubeletDataSymlinkAndFollowsItsSwap() throws IOException {
        var v1 = Files.createDirectory(dir.resolve("..2026_01_01_v1"));
        writeKeys(v1, "AKIA1", "secret1");
        var dataLink = dir.resolve("..data");
        Files.createSymbolicLink(dataLink, v1.getFileName());
        for (var name : new String[] {S3RepoCredentials.ACCESS_KEY_FILE, S3RepoCredentials.SECRET_KEY_FILE}) {
            Files.createSymbolicLink(dir.resolve(name), Path.of("..data", name));
        }
        var provider = newProvider();
        assertKeys(provider, "AKIA1", "secret1");

        var v2 = Files.createDirectory(dir.resolve("..2026_01_02_v2"));
        writeKeys(v2, "AKIA2", "secret2");
        var tmpLink = Files.createSymbolicLink(dir.resolve("..data_tmp"), v2.getFileName());
        Files.move(tmpLink, dataLink, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

        advance(INTERVAL);
        assertKeys(provider, "AKIA2", "secret2");
    }

    @Test
    void prefersDataDirOverTopLevelFiles() throws IOException {
        writeKeys(dir, "TOPLEVEL", "toplevel");
        var data = Files.createDirectory(dir.resolve("..data"));
        writeKeys(data, "AKIA1", "secret1");
        assertKeys(newProvider(), "AKIA1", "secret1");
    }
}
