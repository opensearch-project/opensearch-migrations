package org.opensearch.migrations.transform.shim;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.time.Duration;
import java.util.Base64;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.opensearch.migrations.transform.JavascriptTransformer;
import org.opensearch.migrations.transform.shim.netty.BasicAuthSigningHandler;
import org.opensearch.migrations.transform.shim.netty.SigV4SigningHandler;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import io.netty.channel.ChannelHandler;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

import static org.opensearch.migrations.transform.shim.TransformationShimProxy.HTTPS_SCHEME;

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

        @Parameter(
            names = {"--sigv4-service-region"},
            description = "Enable SigV4 signing with the given service and region (format: 'service,region', "
                + "e.g. 'es,us-east-1'). Uses the default AWS credential chain."
        )
        public String sigV4ServiceRegion;

        @Parameter(
            names = {"--target-username"},
            description = "Username for basic auth on the target backend."
        )
        public String targetUsername;

        @Parameter(
            names = {"--target-password"},
            description = "Password for basic auth on the target backend."
        )
        public String targetPassword;

        @Parameter(
            names = {"--auth-header-value"},
            description = "Static Authorization header value to set on every request."
        )
        public String authHeaderValue;

        @Parameter(
            names = {"--remove-auth-header"},
            description = "Remove the Authorization header from every request (no-auth mode)."
        )
        public boolean removeAuthHeader;

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

        // Build auth handler based on CLI args (same pattern as replayer's buildAuthTransformerFactory)
        Supplier<ChannelHandler> authHandlerSupplier = buildAuthHandler(params);

        var proxy = new TransformationShimProxy(
            params.listenPort,
            URI.create(params.backendUri),
            reqTransformer,
            respTransformer,
            null, // no frontend TLS
            params.insecureBackend,
            Duration.ofSeconds(params.timeoutSeconds),
            params.maxConcurrentRequests,
            authHandlerSupplier
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

    /**
     * Build the auth handler supplier based on CLI args.
     * Same pattern as the replayer's buildAuthTransformerFactory in TrafficReplayer.java.
     * Returns null for no-auth (no handler added to pipeline).
     */
    static Supplier<ChannelHandler> buildAuthHandler(Parameters params) {
        long authOptionsSpecified = Stream.of(
            params.removeAuthHeader,
            params.authHeaderValue != null,
            params.sigV4ServiceRegion != null,
            params.targetUsername != null || params.targetPassword != null
        ).filter(b -> b).count();

        if (authOptionsSpecified > 1) {
            throw new ParameterException(
                "Cannot specify more than one auth option: "
                    + "--sigv4-service-region, --target-username/--target-password, "
                    + "--auth-header-value, --remove-auth-header"
            );
        }

        String authHeader = params.authHeaderValue;
        if (params.targetUsername != null || params.targetPassword != null) {
            if (params.targetUsername == null || params.targetPassword == null) {
                throw new ParameterException(
                    "Both --target-username and --target-password must be specified"
                );
            }
            authHeader = "Basic " + Base64.getEncoder().encodeToString(
                (params.targetUsername + ":" + params.targetPassword).getBytes(StandardCharsets.UTF_8));
        }

        if (authHeader != null) {
            String headerValue = authHeader;
            log.info("Basic/static auth enabled");
            return () -> new BasicAuthSigningHandler(headerValue);
        } else if (params.sigV4ServiceRegion != null) {
            var parts = params.sigV4ServiceRegion.split(",", 2);
            if (parts.length != 2) {
                throw new ParameterException(
                    "--sigv4-service-region must be 'service,region' (e.g. 'es,us-east-1')"
                );
            }
            String service = parts[0].trim();
            String region = parts[1].trim();
            var credentials = DefaultCredentialsProvider.create();
            log.info("SigV4 signing enabled: service={}, region={}", service, region);
            return () -> new SigV4SigningHandler(credentials, service, region, HTTPS_SCHEME);
        } else if (params.removeAuthHeader) {
            log.info("Auth header removal enabled");
            return () -> new BasicAuthSigningHandler(null);
        } else {
            // No auth — no handler in pipeline (default)
            return null;
        }
    }

    private static String loadScript(String path) throws IOException {
        if (path == null || path.isEmpty()) {
            return "(function(msg) { return msg; })";
        }
        return Files.readString(Path.of(path));
    }
}
