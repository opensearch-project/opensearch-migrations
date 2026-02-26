package org.opensearch.migrations.transform.shim;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.opensearch.migrations.transform.JavascriptTransformer;

import lombok.extern.slf4j.Slf4j;

/**
 * Watches transform JS files for changes and hot-reloads them into their
 * corresponding {@link ReloadableTransformer} instances.
 */
@Slf4j
public class TransformFileWatcher implements Runnable, AutoCloseable {
    private final Map<Path, ReloadableTransformer> watchedFiles;
    private final WatchService watchService;
    private final Map<WatchKey, Path> keyToDirMap = new HashMap<>();
    private volatile boolean running = true;

    public TransformFileWatcher(Map<Path, ReloadableTransformer> watchedFiles) throws IOException {
        this.watchedFiles = watchedFiles;
        this.watchService = FileSystems.getDefault().newWatchService();

        // Register parent directories (WatchService watches directories, not files)
        for (Path file : watchedFiles.keySet()) {
            Path dir = file.getParent();
            if (!keyToDirMap.containsValue(dir)) {
                WatchKey key = dir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
                keyToDirMap.put(key, dir);
                log.info("Watching directory {} for transform changes", dir);
            }
        }
    }

    @Override
    public void run() {
        log.info("Transform file watcher started, watching {} files", watchedFiles.size());
        while (running) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            Path dir = keyToDirMap.get(key);
            if (dir == null) {
                key.reset();
                continue;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;

                @SuppressWarnings("unchecked")
                Path changed = dir.resolve(((WatchEvent<Path>) event.context()).context());
                ReloadableTransformer transformer = watchedFiles.get(changed);
                if (transformer != null) {
                    reloadTransformer(changed, transformer);
                }
            }
            key.reset();
        }
    }

    private void reloadTransformer(Path path, ReloadableTransformer transformer) {
        try {
            String script = ShimMain.JS_POLYFILL + Files.readString(path);
            transformer.reload(() -> new JavascriptTransformer(script, new LinkedHashMap<>()));
            log.info("Hot-reloaded transform: {}", path);
        } catch (Exception e) {
            log.error("Failed to reload transform {}, keeping previous version", path, e);
        }
    }

    @Override
    public void close() throws IOException {
        running = false;
        watchService.close();
    }
}
