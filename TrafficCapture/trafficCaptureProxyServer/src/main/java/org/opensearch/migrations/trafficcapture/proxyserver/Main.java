package org.opensearch.migrations.trafficcapture.proxyserver;

import lombok.NonNull;
import lombok.SneakyThrows;
import org.opensearch.common.settings.Settings;
import org.opensearch.migrations.trafficcapture.FileConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.IConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.KafkaCaptureFactory;
import org.opensearch.migrations.trafficcapture.proxyserver.netty.NettyScanningHttpProxy;
import org.opensearch.security.ssl.DefaultSecurityKeyStore;
import org.opensearch.security.ssl.util.SSLConfigConstants;

import javax.net.ssl.SSLEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Supplier;

public class Main {

    private final static String HTTPS_CONFIG_PREFIX = "plugins.security.ssl.http.";

    @SneakyThrows
    private static Settings getSettings(@NonNull String configFile) {
        var builder = Settings.builder();
        try (var lines = Files.lines(Paths.get(configFile))) {
            lines
                    .map(line->Optional.of(line.indexOf('#')).filter(i->i>=0).map(i->line.substring(0, i)).orElse(line))
                    .filter(line->line.startsWith(HTTPS_CONFIG_PREFIX) && line.contains(":"))
                    .forEach(line->{
                        var parts = line.split(": *", 2);
                        builder.put(parts[0], parts[1]);
                    });
        }
        builder.put(SSLConfigConstants.SECURITY_SSL_TRANSPORT_ENABLED, false);
        builder.put("path.home", configFile);
        return builder.build();
    }

    private static IConnectionCaptureFactory getConnectionCaptureFactory(String kafkaPropsPath) throws IOException {
        //return new FileConnectionCaptureFactory("./traceLogs");
        return new KafkaCaptureFactory(kafkaPropsPath);
    }

    public static void main(String[] args) throws InterruptedException, IOException {

        String kafkaPropsPath = args[0];
        var sksOp = Optional.ofNullable(args.length <= 1 ? null : Optional.class)
                .map(o->new DefaultSecurityKeyStore(getSettings(args[1]),
                        args.length > 1 ? Paths.get(args[2]) : null));

        sksOp.ifPresent(x->x.initHttpSSLConfig());
        var proxy = new NettyScanningHttpProxy(sksOp.isPresent() ? 443 : 80);

        try {
            proxy.start("localhost", 9200,
                    sksOp.map(sks-> (Supplier<SSLEngine>) () -> {
                        try {
                            var sslEngine = sks.createHTTPSSLEngine();
                            return sslEngine;
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }).orElse(null), getConnectionCaptureFactory(kafkaPropsPath));
        } catch (Exception e) {
            System.err.println(e);
            e.printStackTrace();
            throw e;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                System.err.println("Received shutdown signal.  Trying to shutdown cleanly");
                proxy.stop();
                System.err.println("Done stopping the proxy.");
            } catch (InterruptedException e) {
                System.err.println("Caught exception while shutting down: "+e);
                throw new RuntimeException(e);
            }
        }));
        // This loop just gives the main() function something to do while the netty event loops
        // work in the background.
        while (true) {
            // TODO: The kill signal will cause the sleep to throw an InterruptedException,
            // which may not be what we want to do - it seems like returning an exit code of 0
            // might make more sense.  This is something to research - e.g. by seeing if there's
            // specific behavior that POSIX recommends/requires.
            Thread.sleep(60*1000);
        }
    }
}
