package org.opensearch.migrations.testutils;

import java.net.URI;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import lombok.Getter;
import lombok.SneakyThrows;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;

public class ToxiProxyWrapper implements AutoCloseable{
    public static final String TOXIPROXY_IMAGE_NAME = "ghcr.io/shopify/toxiproxy:2.9.0";
    public static final int PORT = 8666;

    private final ToxiproxyContainer proxyContainer;
    @Getter
    private Proxy proxy;

    public ToxiProxyWrapper(Network network) {
        super();
        proxyContainer = new ToxiproxyContainer(TOXIPROXY_IMAGE_NAME).withAccessToHost(true).withNetwork(network);
        proxyContainer.start();
    }

    @SneakyThrows
    public Proxy start(String targetHostname, int targetPort) {
        assert proxy == null : "should only call start() once";
        var toxiproxyClient = new ToxiproxyClient(proxyContainer.getHost(), proxyContainer.getControlPort());
        proxy = toxiproxyClient.createProxy(
            "proxy",
            "0.0.0.0:" + PORT,
            targetHostname + ":" + targetPort
        );
        return proxy;
    }

    @SneakyThrows
    public URI getProxyURI() {
        return new URI(getProxyUriAsString());
    }

    @SneakyThrows
    public String getProxyUriAsString() {
        return "http://" + proxyContainer.getHost() + ":" + proxyContainer.getMappedPort(PORT);
    }

    @Override
    public void close() {
        proxyContainer.close();
    }

    @SneakyThrows
    public void enable() {
        assert proxy != null : "must call start first";
        proxy.enable();
    }

    @SneakyThrows
    public void disable() {
        assert proxy != null : "must call start first";
        proxy.disable();
    }
}
