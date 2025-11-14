package org.opensearch.migrations.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

import lombok.Lombok;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class FileSystemUtils {
    private FileSystemUtils() {}

    public static void deleteDirectories(String... directoryPaths) throws IOException {
        for (String dirPath : directoryPaths) {
            if (dirPath != null) {
                FileSystemUtils.deleteTree(Paths.get(dirPath), true);
            }
        }
    }

    /**
     * Recursively deletes a directory tree.
     * 
     * @param path The path to delete
     * @param deleteRootToo Whether to delete the root directory itself
     * @throws IOException if deletion fails
     */
    public static void deleteTree(@NonNull Path path, boolean deleteRootToo) throws IOException {
        log.atDebug().setMessage("Deleting directory tree at {}").addArgument(path).log();
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    if (deleteRootToo || !p.equals(path)) {
                        Files.delete(p);
                    }
                } catch (IOException e) {
                    throw Lombok.sneakyThrow(e);
                }
            });
        } catch (NoSuchFileException e) {
            log.atInfo().setMessage("Deletion skipped - directory not found: {}").addArgument(path).log();
            return;
        }
        log.atInfo().setMessage("Successfully deleted directory tree at {}").addArgument(path).log();
    }
}
