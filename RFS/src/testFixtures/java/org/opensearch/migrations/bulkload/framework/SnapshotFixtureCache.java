package org.opensearch.migrations.bulkload.framework;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;

/**
 * Caches snapshot fixture data on disk so that tests don't need to start Docker containers
 * on every run. First run populates the cache; subsequent runs reuse it.
 *
 * <p>Cache directory is resolved in order:
 * <ol>
 *   <li>System property {@code snapshot.fixture.cache.dir}</li>
 *   <li>Environment variable {@code SNAPSHOT_FIXTURE_CACHE_DIR}</li>
 *   <li>Default: {@code ~/.gradle/snapshot-fixture-cache/}</li>
 * </ol>
 *
 * <p>Set system property {@code snapshot.fixture.cache.disable=true} to bypass caching entirely.
 */
@Slf4j
public class SnapshotFixtureCache {

    private static final String CACHE_DIR_PROPERTY = "snapshot.fixture.cache.dir";
    private static final String CACHE_DIR_ENV = "SNAPSHOT_FIXTURE_CACHE_DIR";
    private static final String CACHE_DISABLE_PROPERTY = "snapshot.fixture.cache.disable";

    private final Path cacheDir;

    public SnapshotFixtureCache() {
        this.cacheDir = resolveCacheDir();
    }

    public boolean isDisabled() {
        return "true".equalsIgnoreCase(System.getProperty(CACHE_DISABLE_PROPERTY));
    }

    /**
     * Returns the cached snapshot directory for the given key, or null if not cached.
     * If cached, copies the data into {@code targetDir}.
     */
    public boolean restoreIfCached(String cacheKey, Path targetDir) {
        if (isDisabled()) {
            return false;
        }
        Path cached = cacheDir.resolve(sanitize(cacheKey));
        if (!Files.isDirectory(cached)) {
            log.info("Snapshot fixture cache MISS: {}", cacheKey);
            return false;
        }
        try {
            copyDirectory(cached, targetDir);
            log.info("Snapshot fixture cache HIT: {} -> {}", cacheKey, targetDir);
            return true;
        } catch (IOException e) {
            log.warn("Failed to restore cached fixture {}, will regenerate", cacheKey, e);
            return false;
        }
    }

    /**
     * Stores the snapshot data from {@code sourceDir} into the cache under {@code cacheKey}.
     */
    public void store(String cacheKey, Path sourceDir) {
        if (isDisabled()) {
            return;
        }
        Path cached = cacheDir.resolve(sanitize(cacheKey));
        try {
            // Remove stale cache entry if present
            if (Files.exists(cached)) {
                deleteDirectory(cached);
            }
            Files.createDirectories(cached);
            copyDirectory(sourceDir, cached);
            log.info("Snapshot fixture cached: {} ({} files)", cacheKey, countFiles(cached));
        } catch (IOException e) {
            log.warn("Failed to cache fixture {}", cacheKey, e);
        }
    }

    private static Path resolveCacheDir() {
        String dir = System.getProperty(CACHE_DIR_PROPERTY);
        if (dir == null) {
            dir = System.getenv(CACHE_DIR_ENV);
        }
        if (dir == null) {
            dir = System.getProperty("user.home") + "/.gradle/snapshot-fixture-cache";
        }
        return Path.of(dir);
    }

    private static String sanitize(String key) {
        return key.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static void copyDirectory(Path src, Path dest) throws IOException {
        try (Stream<Path> walk = Files.walk(src)) {
            walk.forEach(source -> {
                Path target = dest.resolve(src.relativize(source));
                try {
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to copy " + source + " to " + target, e);
                }
            });
        }
    }

    private static void deleteDirectory(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(java.io.File::delete);
        }
    }

    private static long countFiles(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }
}
