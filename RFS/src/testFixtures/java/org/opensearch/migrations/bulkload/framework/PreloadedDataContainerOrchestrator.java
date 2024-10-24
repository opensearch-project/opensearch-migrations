package org.opensearch.migrations.bulkload.framework;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.google.common.collect.Streams;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.jetbrains.annotations.NotNull;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

/**
 * This class caches preloaded data runs by bringing up a base Elasticsearch/OpenSearch container
 * and a traffic generator container alongside it.  The generator sends requests to populate the
 * cluster and once done, the cluster is committed to a new docker image.
 *
 * The docker image will have a name that is a hash of the generator and search container's
 * image names + the arguments, so if either of those change, so should any docker cached version
 * of the preloaded source image that we're building.
 */
@Slf4j
public class PreloadedDataContainerOrchestrator {
    public static String PRELOADED_IMAGE_BASE_NAME = "org/opensearch/migrations/preloaded_source_";
    public static int PULL_TIMEOUT_SECONDS = 600;

    @EqualsAndHashCode
    @AllArgsConstructor
    private static class HashHelper {
        String baseSourceImageId;
        String dataLoaderImageId;
        String[] dataLoaderArgs;
    }

    private final SearchClusterContainer.ContainerVersion baseSourceVersion;
    private final String serverNameAlias;
    private final String dataLoaderImageName;
    private final String[] generatorContainerArgs;

    public PreloadedDataContainerOrchestrator(
        SearchClusterContainer.ContainerVersion baseSourceVersion,
        String serverNameAlias,
        String dataLoaderImageName,
        String[] generatorContainerArgs
    ) {
        this.baseSourceVersion = baseSourceVersion;
        this.serverNameAlias = serverNameAlias;
        this.dataLoaderImageName = dataLoaderImageName;
        this.generatorContainerArgs = generatorContainerArgs;
    }

    public String getReadyImageName(boolean pullIfUnavailable) throws IOException, InterruptedException {
        var imageName = getImageName();
        var dockerClient = createDockerClient();
        var tag = Integer.toString(Math.abs(getHashCodeOfImagesAndArgs(dockerClient, pullIfUnavailable)));
        if (getExistingImage(dockerClient, imageName, tag, false) == null) {
            makeNewImage(dockerClient, imageName, tag);
        }
        return formatFullImageName(imageName, tag);
    }

    String[] getImageAndTagArray(String imageName) {
        var imageTagArray = imageName.split(":");
        if (imageTagArray.length != 2) {
            throw new IllegalArgumentException(
                "Base source image [" + baseSourceVersion.imageName + "] name isn't of the form .*:.*"
            );
        }
        return imageTagArray;
    }

    String getImageId(DockerClient dockerClient, String imageName, boolean pullIfUnavailable)
        throws InterruptedException {
        var imageAndTagArr = getImageAndTagArray(imageName);
        var image = getExistingImage(dockerClient, imageAndTagArr[0], imageAndTagArr[1], pullIfUnavailable);
        if (image == null) {
            throw new IllegalStateException(
                "Base source image doesn't exist [" + baseSourceVersion.imageName + "].  Please build/pull it first."
            );
        }
        return image.getId();
    }

    private int getHashCodeOfImagesAndArgs(DockerClient dockerClient, boolean pullIfUnavailable)
        throws InterruptedException {
        var sourceImageId = getImageId(dockerClient, baseSourceVersion.imageName, pullIfUnavailable);
        var dataLoaderImageId = getImageId(dockerClient, dataLoaderImageName, pullIfUnavailable);
        var rval = Objects.hash(sourceImageId, dataLoaderImageId, Arrays.hashCode(generatorContainerArgs));
        log.atInfo().setMessage("{}")
            .addArgument(() ->
                "sourceImageId=" + sourceImageId
                    + " dataLoaderImageId=" + dataLoaderImageId
                    + " args=" + Arrays.stream(generatorContainerArgs).collect(Collectors.joining())
                    + " hash: " + rval)
            .log();
        return rval;
    }

    private String getImageName() {
        return PRELOADED_IMAGE_BASE_NAME + baseSourceVersion.getVersion().toString().replace(" ", "_").toLowerCase();
    }

    private static DockerClient createDockerClient() {
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        var httpClient = new ApacheDockerHttpClient.Builder().dockerHost(config.getDockerHost())
            .sslConfig(config.getSSLConfig())
            .maxConnections(100)
            .connectionTimeout(Duration.ofSeconds(30))
            .responseTimeout(Duration.ofSeconds(45))
            .build();

        return DockerClientImpl.getInstance(config, httpClient);
    }

    private static Image getExistingImage(
        DockerClient dockerClient,
        String imageName,
        String tag,
        boolean pullIfUnavailable
    ) throws InterruptedException {
        var fullName = formatFullImageName(imageName, tag);
        for (var image : dockerClient.listImagesCmd().exec()) {
            for (String s : image.getRepoTags()) {
                if (fullName.equals(s)) {
                    return image;
                }
            }
        }
        if (pullIfUnavailable) {
            log.warn("Image not found. Pulling image: " + fullName);
            dockerClient.pullImageCmd(imageName)
                .withTag(tag)
                .exec(new PullImageResultCallback())
                .awaitCompletion(PULL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return getExistingImage(dockerClient, imageName, tag, false);
        }
        return null;
    }

    @NotNull
    private static String formatFullImageName(String imageName, String tag) {
        return imageName + ":" + tag;
    }

    private void makeNewImage(DockerClient dockerClient, String imageName, String tag) throws IOException,
        InterruptedException {
        var imageResponse = dockerClient.inspectImageCmd(baseSourceVersion.imageName).exec();
        var originalEntrypoint = imageResponse.getConfig().getEntrypoint();
        var originalCmd = imageResponse.getConfig().getCmd();
        final var replacementCommand = Streams.concat(
            Stream.of("/bin/sh", "-c"),
            Stream.of(
                Streams.concat(
                    Optional.ofNullable(originalEntrypoint).stream().flatMap(Arrays::stream),
                    Optional.ofNullable(originalCmd).stream().flatMap(Arrays::stream),
                    Stream.of("&", "tail", "-f", "/dev/null")
                ).collect(Collectors.joining(" "))
            )
        ).toArray(String[]::new);

        try (
            var network = Network.newNetwork();
            var serverContainer = new SearchClusterContainer(baseSourceVersion).withNetwork(network)
                .withNetworkAliases(serverNameAlias)
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint(replacementCommand))
        ) {
            serverContainer.start();
            var serverContainerId = serverContainer.getContainerId();

            // execStartSource(dockerClient, serverContainerId);
            runGeneratorContainerToCompletion(dockerClient, network);
            makeFlushRequestToSourceServer(serverContainer);
            parkSourceContainer(dockerClient, serverContainerId);
            commitSourceToNewImage(dockerClient, serverContainerId, imageName, tag);
        }
    }

    private void execStartSource(DockerClient dockerClient, String serverContainerId) {
        var imageResponse = dockerClient.inspectImageCmd(baseSourceVersion.imageName).exec();
        var originalEntrypoint = imageResponse.getConfig().getEntrypoint();
        var originalCmd = imageResponse.getConfig().getCmd();

        var conjoinedOriginalCommand = Streams.concat(
            Optional.ofNullable(originalEntrypoint).stream().flatMap(Arrays::stream),
            Optional.ofNullable(originalCmd).stream().flatMap(Arrays::stream)
        ).toArray(String[]::new);

        try (var execCmd = dockerClient.execCreateCmd(serverContainerId)) {
            var response = execCmd.withCmd(conjoinedOriginalCommand)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();

            dockerClient.execStartCmd(response.getId()).exec(new ExecStartResultCallback(System.out, System.err));
        }
    }

    private void runGeneratorContainerToCompletion(DockerClient dockerClient, Network network) throws IOException {
        try (
            var clientContainer = new GenericContainer<>(dataLoaderImageName).withNetwork(network)
                .withCommand(generatorContainerArgs)
        ) {
            clientContainer.start();

            try (
                var clientCmd = dockerClient.waitContainerCmd(clientContainer.getContainerId());
                var result = clientCmd.exec(new WaitContainerResultCallback())
            ) {
                int statusCode = result.awaitStatusCode();
                if (statusCode != 0) {
                    throw new IllegalStateException("Load generator client container exited with code " + statusCode);
                }
            }
        }
    }

    private static void makeFlushRequestToSourceServer(SearchClusterContainer serverContainer) throws IOException {
        try (var httpClient = HttpClients.createDefault()) {
            var request = new HttpPost("http://localhost:" + serverContainer.getMappedPort(9200) + "/_flush");
            request.setEntity(new StringEntity(""));  // Set an empty body for the POST request

            try (var response = httpClient.execute(request)) {
                int statusCode = response.getStatusLine().getStatusCode();
                if (statusCode != 200) {
                    throw new IllegalStateException("Failed to flush source. Response code: " + statusCode);
                }
                log.info("Elasticsearch indices flushed successfully.");
            }
        }
    }

    /**
     * Here, the word 'park' means to put the source container into a quiescent state where a snapshot could
     * be taken and the container could be restarted with the original entrypoint starting up cleanly.
     */
    private static void parkSourceContainer(DockerClient dockerClient, String serverContainerId)
        throws InterruptedException {
        try (var execCmd = dockerClient.execCreateCmd(serverContainerId)) {
            final var script = ""
                + "export PID=`ps -e -o pid=,comm= | sort -n | grep java | sed 's/ \\+/ /g' | sed 's/^ //' | cut -d ' ' -f 1` && "
                + "echo pid=${PID} && "
                + "kill -15 ${PID} && "
                + "while kill -0 ${PID} 2>/dev/null; do sleep 1; done && "
                + "rm -f `find . -name \\*.lock` && "
                + "sleep 2 && "
                + "echo done";
            var syncResponse = execCmd.withCmd("/bin/bash", "-c", script)
                .withAttachStderr(true)
                .withAttachStdout(true)
                .exec();
            var outputStream = new ByteArrayOutputStream();

            // Start the exec command and capture the output
            var result = dockerClient.execStartCmd(syncResponse.getId())
                .exec(new ExecStartResultCallback(outputStream, outputStream))
                .awaitCompletion(20, TimeUnit.SECONDS);
            log.info("Source filesystem sync + lock removal completed w/ result=" + result);
        }
    }

    private static void commitSourceToNewImage(
        DockerClient dockerClient,
        String serverContainerId,
        String imageName,
        String tag
    ) {
        try (
            var commitCmd = dockerClient.commitCmd(serverContainerId)
                .withLabels(Map.of("org.testcontainers.sessionId", "none"))
                .withRepository(imageName)
                .withTag(tag)
        ) {
            var result = commitCmd.exec();
            log.warn("done commmitting " + imageName + ":" + tag + " w/ result=" + result);
        }
    }
}
