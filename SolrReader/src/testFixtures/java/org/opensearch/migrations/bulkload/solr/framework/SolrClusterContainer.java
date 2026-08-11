package org.opensearch.migrations.bulkload.solr.framework;

import java.time.Duration;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Testcontainers wrapper for Solr, following the project's container pattern.
 * Uses GenericContainer directly to avoid testcontainers-solr module compatibility issues.
 *
 * <p>Supports two modes:
 * <ul>
 *   <li>Standalone (default): {@code new SolrClusterContainer(version)} — single-node, core-based</li>
 *   <li>SolrCloud: {@code SolrClusterContainer.cloud(version)} — embedded ZooKeeper, collection-based with sharding</li>
 * </ul>
 */
@Slf4j
public class SolrClusterContainer extends GenericContainer<SolrClusterContainer> {

    public static final SolrVersion SOLR_6 = new SolrVersion("6.6.0");
    public static final SolrVersion SOLR_7 = new SolrVersion("7.7.3");
    public static final SolrVersion SOLR_8 = new SolrVersion("8.11.4");
    public static final SolrVersion SOLR_9 = new SolrVersion("9.8.1");
    /** Earliest Solr release with an S3 backup repository (S3BackupRepository / SIP-12 landed in 8.10). */
    public static final SolrVersion SOLR_8_10 = new SolrVersion("8.10.1");

    @Getter
    private final SolrVersion solrVersion;
    @Getter
    private final boolean cloudMode;

    /** Create a standalone Solr container. */
    @SuppressWarnings("resource")
    public SolrClusterContainer(SolrVersion version) {
        this(version, false);
    }

    @SuppressWarnings("resource")
    private SolrClusterContainer(SolrVersion version, boolean cloudMode) {
        super(DockerImageName.parse("solr:" + version.tag()));
        this.solrVersion = version;
        this.cloudMode = cloudMode;
        this.withExposedPorts(8983);
        if (cloudMode) {
            this.withCommand("solr", "-c", "-f")
                .waitingFor(Wait.forHttp("/solr/admin/collections?action=LIST&wt=json")
                    .forPort(8983)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)));
        } else {
            this.withCommand("solr-precreate", "dummy")
                .waitingFor(Wait.forHttp("/solr/admin/cores?action=STATUS&indexInfo=false&wt=json")
                    .forPort(8983)
                    .forStatusCode(200)
                    .forResponsePredicate(body -> body.contains("\"dummy\""))
                    .withStartupTimeout(Duration.ofMinutes(2)));
        }
    }

    /** Create a SolrCloud container with embedded ZooKeeper. */
    public static SolrClusterContainer cloud(SolrVersion version) {
        return new SolrClusterContainer(version, true);
    }

    /** A node joining an external ZooKeeper rather than an embedded one; see {@link SolrCloudCluster}. */
    @SuppressWarnings("resource")
    public static SolrClusterContainer cloudNode(SolrVersion version, String zkHost) {
        var container = new SolrClusterContainer(version, true);
        container.withEnv("ZK_HOST", zkHost);
        return container;
    }

    /** Classpath resource (shipped in this module's test fixtures) wiring Solr's S3 backup repository. */
    private static final String S3_BACKUP_SOLR_XML = "solr-s3-backup.xml";

    /**
     * Create a SolrCloud container whose backup repository writes to an S3-compatible endpoint
     * (e.g. LocalStack). Mirrors the layout Solr's {@code S3BackupRepository} expects: it copies an
     * S3-enabled {@code solr.xml}, loads the {@code s3-repository} contrib jar, and points the
     * repository at the given bucket/region/endpoint.
     *
     * @param network         shared docker network the S3 endpoint is reachable on
     * @param bucketName      S3 bucket the backup repository writes to
     * @param region          S3 region
     * @param localstackAlias network alias of the S3 endpoint (resolved as {@code http://alias:4566})
     */
    @SuppressWarnings("resource")
    public static SolrClusterContainer cloudWithS3Backup(
        SolrVersion version, Network network, String bucketName, String region, String localstackAlias
    ) {
        var container = new SolrClusterContainer(version, true);
        container.withNetwork(network)
            .withCopyFileToContainer(
                MountableFile.forClasspathResource(S3_BACKUP_SOLR_XML),
                "/var/solr/data/solr.xml")
            .withEnv("AWS_ACCESS_KEY_ID", "test")
            .withEnv("AWS_SECRET_ACCESS_KEY", "test")
            .withEnv("SOLR_OPTS",
                "-DS3_BUCKET_NAME=" + bucketName
                    + " -DS3_REGION=" + region
                    + " -DS3_ENDPOINT=http://" + localstackAlias + ":4566")
            .withEnv("SOLR_SECURITY_MANAGER_ENABLED", "false")
            .withCreateContainerCmdModifier(cmd -> cmd.withUser("root"))
            // The s3-repository jar ships under dist/ but isn't on the contrib lib path by default;
            // copy it in before launching Solr in cloud mode with the security manager disabled.
            .withCommand("bash", "-c",
                "cp /opt/solr/dist/solr-s3-repository-*.jar "
                    + "/opt/solr/contrib/s3-repository/lib/ && "
                    + "exec solr -c -f -force");
        return container;
    }

    public String getSolrUrl() {
        return "http://" + getHost() + ":" + getMappedPort(8983);
    }

    @Override
    public void start() {
        log.atInfo().setMessage("Starting Solr container: {} (cloud={})").addArgument(solrVersion).addArgument(cloudMode).log();
        super.start();
        if (securityJson != null) {
            applySecurityJson();
        }
    }

    // --- Secured fixture ---

    /** Applied after startup, not baked in — see {@link #securedCloud}. */
    private String securityJson;

    /** Role used by the single-user convenience overload. */
    private static final String DEFAULT_TEST_ROLE = "solr_test_role";

    /**
     * SolrCloud with BasicAuth + RuleBasedAuthorization, mirroring security.json. Solr permissions
     * are not hierarchical, so every endpoint needed must be granted explicitly. security.json is
     * uploaded to ZooKeeper after startup — the readiness probe is unauthenticated and would never
     * pass against an already-secured Solr.
     *
     * @param credentials     username → password
     * @param userRoles       username → roles
     * @param rolePermissions role → Solr predefined permission names
     */
    public static SolrClusterContainer securedCloud(
        SolrVersion version,
        java.util.Map<String, String> credentials,
        java.util.Map<String, java.util.List<String>> userRoles,
        java.util.Map<String, java.util.List<String>> rolePermissions
    ) {
        for (var user : userRoles.keySet()) {
            if (!credentials.containsKey(user)) {
                throw new IllegalArgumentException("No password supplied for user with roles: " + user);
            }
        }
        var container = new SolrClusterContainer(version, true);
        container.securityJson = buildSecurityJson(credentials, userRoles, rolePermissions);
        return container;
    }

    /** Convenience for one user holding one role that grants {@code permissions}. */
    public static SolrClusterContainer securedCloud(
        SolrVersion version, String username, String password, java.util.List<String> permissions
    ) {
        return securedCloud(version,
            java.util.Map.of(username, password),
            java.util.Map.of(username, java.util.List.of(DEFAULT_TEST_ROLE)),
            java.util.Map.of(DEFAULT_TEST_ROLE, permissions));
    }

    private static String buildSecurityJson(
        java.util.Map<String, String> credentials,
        java.util.Map<String, java.util.List<String>> userRoles,
        java.util.Map<String, java.util.List<String>> rolePermissions
    ) {
        var credentialEntries = new java.util.ArrayList<String>();
        credentials.forEach((user, password) ->
            credentialEntries.add(jsonString(user) + ":" + jsonString(sha256Credential(password))));

        // Permission list is ordered, first match wins, so one entry must name all its roles.
        var permissionToRoles = new java.util.LinkedHashMap<String, java.util.List<String>>();
        rolePermissions.forEach((role, permissions) -> permissions.forEach(permission ->
            permissionToRoles.computeIfAbsent(permission, p -> new java.util.ArrayList<>()).add(role)));

        var permissionEntries = new java.util.ArrayList<String>();
        permissionToRoles.forEach((permission, roles) -> permissionEntries.add(
            "{\"name\":" + jsonString(permission) + ",\"role\":" + jsonArray(roles) + "}"));

        var userRoleEntries = new java.util.ArrayList<String>();
        userRoles.forEach((user, roles) -> userRoleEntries.add(jsonString(user) + ":" + jsonArray(roles)));

        return "{\n"
            + "  \"authentication\": {\n"
            + "    \"blockUnknown\": true,\n"
            + "    \"class\": \"solr.BasicAuthPlugin\",\n"
            + "    \"credentials\": {" + String.join(",", credentialEntries) + "}\n"
            + "  },\n"
            + "  \"authorization\": {\n"
            + "    \"class\": \"solr.RuleBasedAuthorizationPlugin\",\n"
            + "    \"permissions\": [" + String.join(",", permissionEntries) + "],\n"
            + "    \"user-role\": {" + String.join(",", userRoleEntries) + "}\n"
            + "  }\n"
            + "}";
    }

    private static String jsonString(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String jsonArray(java.util.List<String> values) {
        var quoted = values.stream().map(SolrClusterContainer::jsonString).toList();
        return "[" + String.join(",", quoted) + "]";
    }

    /** Solr's Sha256AuthenticationProvider format: base64(sha256(sha256(salt + password))) + " " + base64(salt). */
    private static String sha256Credential(String password) {
        try {
            var salt = new byte[32];
            new java.security.SecureRandom().nextBytes(salt);
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.reset();
            digest.update(salt);
            var hashed = digest.digest(password.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.reset();
            hashed = digest.digest(hashed);
            var encoder = java.util.Base64.getEncoder();
            return encoder.encodeToString(hashed) + " " + encoder.encodeToString(salt);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void applySecurityJson() {
        try {
            copyFileToContainer(
                org.testcontainers.images.builder.Transferable.of(securityJson), "/tmp/security.json");
            var upload = execInContainer("/opt/solr/bin/solr", "zk", "cp",
                "file:/tmp/security.json", "zk:/security.json", "-z", "localhost:9983");
            if (upload.getExitCode() != 0) {
                throw new IllegalStateException(
                    "Failed to upload security.json: " + upload.getStderr() + upload.getStdout());
            }
            // The ZK watch applies asynchronously; wait until anonymous requests are rejected.
            for (int i = 0; i < 60; i++) {
                var probe = execInContainer("sh", "-c",
                    "curl -s -o /dev/null -w '%{http_code}' "
                        + "'http://localhost:8983/solr/admin/collections?action=LIST&wt=json'");
                var code = probe.getStdout().trim();
                if ("401".equals(code) || "403".equals(code)) {
                    log.atInfo().setMessage("security.json active (anonymous request got {})")
                        .addArgument(code).log();
                    return;
                }
                Thread.sleep(1000);
            }
            throw new IllegalStateException("security.json did not take effect within 60s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted applying security.json", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed applying security.json", e);
        }
    }

    @Override
    public void close() {
        log.atInfo().setMessage("Stopping Solr container: {}").addArgument(solrVersion).log();
        log.atDebug().setMessage("Solr logs:\n{}").addArgument(this::getLogs).log();
        super.close();
    }

    // =====================================================================================
    // S3 backup fixtures
    //
    // These factories produce a Solr container whose backup repository writes to an
    // S3-compatible endpoint (e.g. LocalStack), covering the full Solr version x deployment
    // mode matrix. They encapsulate every version/mode-specific launch detail (command, env,
    // wait strategy, contrib-vs-module layout) so callers only pick a factory and then issue
    // Solr REST calls via getSolrUrl() / execInContainer.
    // =====================================================================================

    /** Classpath resource (shipped in this module's test fixtures) wiring Solr's S3 backup repository. */
    private static final String S3_REPOSITORY_RESOURCE = "solr-s3-backup.xml";

    /** Launch topology for the S3-backup factory. */
    public enum Mode {
        /** SolrCloud (embedded ZooKeeper); collection-based; waits on collections LIST. */
        CLOUD,
        /** Standalone single-node; core-based; precreates a core and waits on cores STATUS. */
        STANDALONE
    }

    // NOTE: A cloud-only convenience factory cloudWithS3Backup(SolrVersion, Network, String,
    // String, String) is introduced by PR #3092. It is intentionally NOT defined here to avoid a
    // duplicate-method clash when the two changes merge; callers wanting the Solr 8 cloud case can
    // use withS3Backup(version, Mode.CLOUD, ...). Once #3092 lands, its cloudWithS3Backup can be
    // reduced to a delegate to this general factory.

    /**
     * General-purpose S3-backup Solr fixture covering the full version x mode matrix.
     *
     * <p>Layout handling:
     * <ul>
     *   <li>Solr &le; 8: sharedLib = {@code /opt/solr/contrib/s3-repository/lib} and the
     *       {@code solr-s3-repository-*.jar} is copied from {@code /opt/solr/dist} into it before
     *       launch (the contrib lib dir ships empty in the 8.x image).</li>
     *   <li>Solr &ge; 9: sharedLib = {@code /opt/solr/modules/s3-repository/lib} and
     *       {@code SOLR_MODULES=s3-repository} is exported; the module lib is already complete so
     *       no jar copy is performed.</li>
     * </ul>
     * The {@code sharedLib} value is fed to solr.xml via {@code -DsharedLib} in {@code SOLR_OPTS}
     * (solr.xml uses {@code <str name="sharedLib">${sharedLib:/opt/solr/contrib/s3-repository/lib}</str>}).
     *
     * <p>Mode handling:
     * <ul>
     *   <li>{@link Mode#CLOUD}: SolrCloud; waits on {@code collections?action=LIST}.</li>
     *   <li>{@link Mode#STANDALONE}: single node; precreates {@code coreOrCollection} and waits on
     *       {@code cores?action=STATUS} whose body contains that core name.</li>
     * </ul>
     *
     * @param version          Solr version (e.g. {@link #SOLR_8_10}, {@link #SOLR_8}, {@link #SOLR_9})
     * @param mode             {@link Mode#CLOUD} or {@link Mode#STANDALONE}
     * @param network          shared docker network (must reach the LocalStack alias)
     * @param coreOrCollection core name to precreate in STANDALONE mode (the wait strategy looks for
     *                         it); ignored in CLOUD mode
     * @param bucketName       S3 bucket name
     * @param region           S3 region
     * @param localstackAlias  LocalStack network alias; S3 endpoint becomes {@code http://<alias>:4566}
     */
    @SuppressWarnings("resource")
    public static SolrClusterContainer withS3Backup(
        SolrVersion version,
        Mode mode,
        Network network,
        String coreOrCollection,
        String bucketName,
        String region,
        String localstackAlias
    ) {
        var container = new SolrClusterContainer(version, mode == Mode.CLOUD);

        String solrOpts = "-DsharedLib=" + s3SharedLibPath(version)
            + " -DS3_BUCKET_NAME=" + bucketName
            + " -DS3_REGION=" + region
            + " -DS3_ENDPOINT=http://" + localstackAlias + ":4566";

        container.withNetwork(network)
            .withCopyFileToContainer(
                MountableFile.forClasspathResource(S3_REPOSITORY_RESOURCE),
                "/var/solr/data/solr.xml")
            .withEnv("AWS_ACCESS_KEY_ID", "test")
            .withEnv("AWS_SECRET_ACCESS_KEY", "test")
            .withEnv("SOLR_OPTS", solrOpts)
            .withEnv("SOLR_SECURITY_MANAGER_ENABLED", "false")
            .withCreateContainerCmdModifier(cmd -> cmd.withUser("root"))
            .withCommand("bash", "-c", s3LaunchCommand(version, mode, coreOrCollection));

        // Solr 9+ loads the S3 repository as a module rather than from a contrib jar.
        if (version.major() >= 9) {
            container.withEnv("SOLR_MODULES", "s3-repository");
        }

        if (mode == Mode.CLOUD) {
            container.waitingFor(Wait.forHttp("/solr/admin/collections?action=LIST&wt=json")
                .forPort(8983)
                .forStatusCode(200)
                .withStartupTimeout(Duration.ofMinutes(3)));
        } else {
            // Standalone is core-based: wait until the precreated core is reported up.
            container.waitingFor(Wait.forHttp("/solr/admin/cores?action=STATUS&indexInfo=false&wt=json")
                .forPort(8983)
                .forStatusCode(200)
                .forResponsePredicate(body -> body.contains("\"" + coreOrCollection + "\""))
                .withStartupTimeout(Duration.ofMinutes(3)));
        }
        return container;
    }

    /** The directory solr.xml's {@code sharedLib} points at, by major version. */
    private static String s3SharedLibPath(SolrVersion version) {
        return version.major() >= 9
            ? "/opt/solr/modules/s3-repository/lib"
            : "/opt/solr/contrib/s3-repository/lib";
    }

    /**
     * Build the {@code bash -c} launch string for the given version/mode. Solr 8 needs the
     * s3-repository jar copied from {@code dist} into the (empty) contrib lib dir before launch;
     * Solr 9 needs no copy (the module lib is complete and enabled via {@code SOLR_MODULES}). The
     * {@code init-var-solr} prefix is idempotent — it only seeds a default solr.xml when one is
     * absent (ours is already mounted) and otherwise just ensures {@code /var/solr/data} layout
     * under the root user.
     */
    private static String s3LaunchCommand(SolrVersion version, Mode mode, String coreOrCollection) {
        var cmd = new StringBuilder("init-var-solr");
        if (version.major() <= 8) {
            cmd.append(" && cp /opt/solr/dist/solr-s3-repository-*.jar ")
               .append(s3SharedLibPath(version)).append('/');
        }
        if (mode == Mode.STANDALONE) {
            cmd.append(" && precreate-core ").append(coreOrCollection)
               .append(" && exec solr-foreground -force");
        } else {
            cmd.append(" && exec solr-foreground -c -force");
        }
        return cmd.toString();
    }

    public record SolrVersion(String tag) {
        public int major() {
            return Integer.parseInt(tag.split("\\.")[0]);
        }

        @Override
        public String toString() {
            return "Solr(" + tag + ")";
        }
    }
}
