package org.opensearch.migrations.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Comparator;

import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class FileSystemUtils {
    private FileSystemUtils() {}

    public static void deleteTree(Path path) throws IOException {
        deleteTree(path, true);
    }
    public static void deleteTree(Path path, boolean deleteRootToo) throws IOException {
        if (path == null) {
            log.atDebug().setMessage("not deleting tree because path=null").log();
            return;
        }
        log.atDebug().setMessage("Deleting tree at {}").addArgument(path).log();
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
            log.atInfo().setMessage("Deletion skipped because {} was not found").addArgument(path).log();
            return;
        }
        log.atInfo().setMessage("Done deleting tree at {}").addArgument(path).log();
    }

}
