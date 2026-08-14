package org.opensearch.migrations.bulkload.solr.framework;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Multi-node SolrCloud: Solr containers on a shared network against one ZooKeeper, with one backup
 * directory bind-mounted into every node. {@link SolrClusterContainer#cloud} is single-node, so its
 * replicas share a JVM and disk. BACKUP writes to {@code location} on each leader's own filesystem,
 * so the shared mount is what makes a multi-node backup land in one place.
 */
@Slf4j
public class SolrCloudCluster implements AutoCloseable {

    private static final String ZOOKEEPER_IMAGE = "zookeeper:3.8";
    private static final String ZOOKEEPER_ALIAS = "zookeeper";
    private static final int ZOOKEEPER_PORT = 2181;

    /** Mount point for the shared backup directory inside every node. */
    public static final String BACKUP_DIR = "/backups";

    private final SolrClusterContainer.SolrVersion version;
    private final int nodeCount;
    private final Network network;
    private final GenericContainer<?> zookeeper;
    private final List<SolrClusterContainer> nodes = new ArrayList<>();

    @Getter
    private final Path hostBackupDir;

    @SuppressWarnings("resource")
    public SolrCloudCluster(SolrClusterContainer.SolrVersion version, int nodeCount, Path hostBackupDir)
        throws IOException {
        this.version = version;
        this.nodeCount = nodeCount;
        this.hostBackupDir = hostBackupDir;
        this.network = Network.newNetwork();

        Files.createDirectories(hostBackupDir);
        hostBackupDir.toFile().setWritable(true, false);

        this.zookeeper = new GenericContainer<>(DockerImageName.parse(ZOOKEEPER_IMAGE))
            .withNetwork(network)
            .withNetworkAliases(ZOOKEEPER_ALIAS)
            .withExposedPorts(ZOOKEEPER_PORT)
            .waitingFor(Wait.forListeningPort());
    }

    @SuppressWarnings("resource")
    public void start() throws Exception {
        log.atInfo().setMessage("Starting ZooKeeper for {}-node SolrCloud cluster").addArgument(nodeCount).log();
        zookeeper.start();

        var zkHost = ZOOKEEPER_ALIAS + ":" + ZOOKEEPER_PORT;
        for (int i = 0; i < nodeCount; i++) {
            var node = SolrClusterContainer.cloudNode(version, zkHost)
                .withNetwork(network)
                .withNetworkAliases("solr" + i)
                .withFileSystemBind(hostBackupDir.toString(), BACKUP_DIR)
                // BACKUP refuses locations outside SOLR_HOME unless they are allow-listed.
                .withEnv("SOLR_OPTS", "-Dsolr.allowPaths=" + BACKUP_DIR);
            nodes.add(node);
        }
        for (var node : nodes) {
            node.start();
        }
        awaitLiveNodes();
    }

    /** CREATE places replicas across live nodes, so all must join first. */
    private void awaitLiveNodes() throws Exception {
        for (int attempt = 0; attempt < 120; attempt++) {
            var status = nodes.get(0).execInContainer("curl", "-s",
                "http://localhost:8983/solr/admin/collections?action=CLUSTERSTATUS&wt=json");
            var body = status.getStdout();
            var marker = "\"live_nodes\":[";
            var start = body.indexOf(marker);
            if (start >= 0) {
                var end = body.indexOf(']', start);
                var live = body.substring(start + marker.length(), end);
                var count = live.isBlank() ? 0 : live.split(",").length;
                if (count >= nodeCount) {
                    log.atInfo().setMessage("SolrCloud cluster ready with {} live node(s)")
                        .addArgument(count).log();
                    return;
                }
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("Only some nodes joined the cluster within 120s");
    }

    public SolrClusterContainer node(int index) {
        return nodes.get(index);
    }

    public List<SolrClusterContainer> nodes() {
        return List.copyOf(nodes);
    }

    public String getSolrUrl() {
        return nodes.get(0).getSolrUrl();
    }

    @Override
    public void close() {
        clearSharedBackupDir();
        nodes.forEach(SolrClusterContainer::close);
        zookeeper.close();
        network.close();
    }

    /** Solr writes as its own uid, so the host cannot delete these; clear them from inside a node. */
    private void clearSharedBackupDir() {
        if (nodes.isEmpty() || !nodes.get(0).isRunning()) {
            return;
        }
        try {
            nodes.get(0).execInContainer("sh", "-c", "rm -rf " + BACKUP_DIR + "/*");
        } catch (Exception e) {
            log.atWarn().setMessage("Could not clear {}: {}")
                .addArgument(BACKUP_DIR).addArgument(e.getMessage()).log();
        }
    }
}
