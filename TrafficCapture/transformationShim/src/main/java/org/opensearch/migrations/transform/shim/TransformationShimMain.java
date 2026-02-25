package org.opensearch.migrations.transform.shim;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;

import org.opensearch.migrations.transform.JavascriptTransformer;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TransformationShimMain {

    public static class Parameters {
        @Parameter(
            required = true,
            names = {"--listenPort"},
            description = "Port for the shim proxy to listen on."
        )
        public int listenPort;

        @Parameter(
            required = true,
            names = {"--backendUri"},
            description = "URI of the backend server to forward transformed requests to."
        )
        public String backendUri;

        @Parameter(
            names = {"--requestTransformScript"},
            description = "Path to a JavaScript file for request transformation."
        )
        public String requestTransformScript;

        @Parameter(
            names = {"--responseTransformScript"},
            description = "Path to a JavaScript file for response transformation."
        )
        public String responseTransformScript;

        @Parameter(
            names = {"--insecureBackend"},
            description = "Trust all backend TLS certificates."
        )
        public boolean insecureBackend;

        @Parameter(
            names = {"--timeout"},
            description = "Backend request timeout in seconds."
        )
        public int timeoutSeconds = 150;

        @Parameter(
            names = {"--maxConcurrentRequests"},
            description = "Maximum number of concurrent in-flight requests."
        )
        public int maxConcurrentRequests = 100;

        @Parameter(
            names = {"--watchTransforms"},
            description = "Watch transform script files for changes and hot-reload."
        )
        public boolean watchTransforms;

        @Parameter(names = {"--help", "-h"}, help = true, description = "Show usage.")
        public boolean help;
    }

    public static void main(String[] args) throws InterruptedException, IOException {
        var params = new Parameters();
        var jCommander = JCommander.newBuilder().addObject(params).build();
        jCommander.setProgramName("TransformationShim");
        try {
            jCommander.parse(args);
        } catch (ParameterException e) {
            System.err.println(e.getMessage());
            jCommander.usage();
            System.exit(1);
        }
        if (params.help) {
            jCommander.usage();
            return;
        }

        var reqScript = loadScript(params.requestTransformScript);
        var respScript = loadScript(params.responseTransformScript);

        ReloadableTransformer reqTransformer = new ReloadableTransformer(
            () -> new JavascriptTransformer(reqScript, null));
        ReloadableTransformer respTransformer = new ReloadableTransformer(
            () -> new JavascriptTransformer(respScript, null));

        var proxy = new TransformationShimProxy(
            params.listenPort,
            URI.create(params.backendUri),
            reqTransformer,
            respTransformer
        );

        if (params.watchTransforms) {
            startFileWatcher(params, reqTransformer, respTransformer);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                log.info("Shutdown signal received, stopping proxy...");
                proxy.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));

        proxy.start();
        log.info("TransformationShim running on port {}", params.listenPort);
        proxy.waitForClose();
    }

    private static void startFileWatcher(
        Parameters params,
        ReloadableTransformer reqTransformer,
        ReloadableTransformer respTransformer
    ) throws IOException {
        var watchService = FileSystems.getDefault().newWatchService();

        // Register parent directories of both script files
        Path reqPath = params.requestTransformScript != null
            ? Path.of(params.requestTransformScript) : null;
        Path respPath = params.responseTransformScript != null
            ? Path.of(params.responseTransformScript) : null;

        if (reqPath != null) {
            reqPath.toAbsolutePath().getParent().register(
                watchService, StandardWatchEventKinds.ENTRY_MODIFY);
        }
        if (respPath != null && (reqPath == null
            || !respPath.toAbsolutePath().getParent().equals(reqPath.toAbsolutePath().getParent()))) {
            respPath.toAbsolutePath().getParent().register(
                watchService, StandardWatchEventKinds.ENTRY_MODIFY);
        }

        Thread watchThread = new Thread(() -> {
            log.info("Watching transform scripts for changes...");
            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key;
                try {
                    key = watchService.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                for (var event : key.pollEvents()) {
                    var changed = (Path) event.context();
                    reloadIfMatch(changed, reqPath, reqTransformer, "request");
                    reloadIfMatch(changed, respPath, respTransformer, "response");
                }
                key.reset();
            }
        }, "transform-watcher");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    private static void reloadIfMatch(
        Path changed, Path scriptPath, ReloadableTransformer transformer, String label
    ) {
        if (scriptPath == null || !changed.toString().equals(scriptPath.getFileName().toString())) {
            return;
        }
        try {
            var newScript = Files.readString(scriptPath);
            transformer.reload(() -> new JavascriptTransformer(newScript, null));
            log.info("Reloaded {} transform from {}", label, scriptPath);
        } catch (IOException e) {
            log.error("Failed to reload {} transform from {}", label, scriptPath, e);
        }
    }

    private static String loadScript(String path) throws IOException {
        if (path == null || path.isEmpty()) {
            return "(function(msg) { return msg; })";
        }
        return Files.readString(Path.of(path));
    }
}
