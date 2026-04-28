package org.opensearch.migrations.image

import groovy.json.JsonOutput
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.opensearch.migrations.common.CommonUtils

class RegistryImageBuildUtils {
    private static boolean builderWarningShown = false

    static class Registry { // Helper object
        final String hostUrl      // For Jib, which runs in the JVM directly (e.g., localhost:5001)
        final String containerUrl // For BuildKit, which runs in a container (e.g., docker-registry:5000)

        Registry(String rawUrl, String localContainerUrl="docker-registry:5000") {
            this.hostUrl = rawUrl
            // Keep the host-visible endpoint for Jib, but make the container-visible endpoint explicit.
            if (rawUrl.startsWith("localhost:")) {
                this.containerUrl = localContainerUrl
            } else {
                this.containerUrl = rawUrl
            }
        }

        String getRegistryDomain() { hostUrl.split('/')[0] }
        boolean isEcr() { registryDomain.contains('.ecr.') && registryDomain.contains('.amazonaws.com') }
    }

    boolean isAggregateBakeRequested(Project rootProject) {
        def startTasks = rootProject.gradle.startParameter.taskNames*.toLowerCase()
        return startTasks.any {
            it.contains("buildimagestoregistry") || it.contains("buildkitbakeall")
        }
    }

    static String resolveBaseImage(Registry registry, String group, String image, String tag) {
        return resolveBaseImageForUrl(registry.hostUrl, group, image, tag)
    }

    static String resolveBaseImageForContainer(Registry registry, String group, String image, String tag) {
        return resolveBaseImageForUrl(registry.containerUrl, group, image, tag)
    }

    private static String resolveBaseImageForUrl(String url, String group, String image, String tag) {
        def formatter = ImageRegistryFormatterFactory.getFormatter(url)
        return formatter.getFullBaseImageIdentifier(url, group, image, tag)
    }

    /**
     * Determine the HTTP scheme for a registry host.
     * Defaults to http for localhost, https for everything else.
     * Can be overridden with -DregistryAllowInsecure=true or REGISTRY_ALLOW_INSECURE=true env var.
     */
    private static String registryScheme(String registryHost) {
        def override = System.getProperty("registryAllowInsecure") ?: System.getenv("REGISTRY_ALLOW_INSECURE")
        if (override != null && !override.isEmpty()) {
            return override.toBoolean() ? "http" : "https"
        }
        return registryHost.startsWith("localhost") ? "http" : "https"
    }

    /**
     * Read a Basic auth token for a registry host from Docker's credential store.
     * Returns null if no credentials are found.
     */
    private static String registryAuthHeader(String registryHost) {
        try {
            def configFile = new File(System.getProperty("user.home"), ".docker/config.json")
            if (!configFile.exists()) return null
            def config = new groovy.json.JsonSlurper().parse(configFile)

            // Check for inline auth first
            def authEntry = config.auths?.get("https://${registryHost}")
                    ?: config.auths?.get(registryHost)
            if (authEntry?.auth) {
                return "Basic ${authEntry.auth}"
            }

            // Try credential helper
            def helperName = config.credHelpers?.get(registryHost) ?: config.credsStore
            if (helperName) {
                def proc = ["docker-credential-${helperName}", "get"].execute()
                proc.outputStream.write("https://${registryHost}".bytes)
                proc.outputStream.close()
                proc.waitFor()
                def output = proc.inputStream.text
                if (output) {
                    def result = new groovy.json.JsonSlurper().parseText(output)
                    if (result?.Username && result?.Secret) {
                        def token = "${result.Username}:${result.Secret}".bytes.encodeBase64().toString()
                        return "Basic ${token}"
                    }
                }
            }
        } catch (Exception ignored) {}
        return null
    }

    /**
     * Query a v2 registry for the digest of an image reference like "host:port/repo:tag"
     * and return "host:port/repo@sha256:...". Throws if the digest cannot be resolved.
     */
    static String resolveDigest(String imageReference) {
        def atIdx = imageReference.indexOf('@')
        if (atIdx >= 0) return imageReference // already has a digest

        def colonIdx = imageReference.lastIndexOf(':')
        def slashIdx = imageReference.lastIndexOf('/')
        String tag = (colonIdx > slashIdx && colonIdx >= 0) ? imageReference.substring(colonIdx + 1) : "latest"
        String repoWithHost = (colonIdx > slashIdx && colonIdx >= 0) ? imageReference.substring(0, colonIdx) : imageReference

        def firstSlash = repoWithHost.indexOf('/')
        if (firstSlash < 0) throw new GradleException("Cannot parse registry host from image reference: ${imageReference}")
        def registryHost = repoWithHost.substring(0, firstSlash)
        def repository = repoWithHost.substring(firstSlash + 1)

        def scheme = registryScheme(registryHost)
        def url = new URL("${scheme}://${registryHost}/v2/${repository}/manifests/${tag}")
        def conn = (HttpURLConnection) url.openConnection()
        conn.setRequestMethod("HEAD")
        conn.setRequestProperty("Accept",
                "application/vnd.docker.distribution.manifest.list.v2+json, " +
                "application/vnd.docker.distribution.manifest.v2+json, " +
                "application/vnd.oci.image.index.v1+json, " +
                "application/vnd.oci.image.manifest.v1+json")
        def authHeader = registryAuthHeader(registryHost)
        if (authHeader) conn.setRequestProperty("Authorization", authHeader)
        conn.setConnectTimeout(5000)
        conn.setReadTimeout(5000)

        if (conn.responseCode != 200) {
            throw new GradleException("Failed to resolve digest for ${imageReference}: registry returned HTTP ${conn.responseCode}")
        }
        def digest = conn.getHeaderField("Docker-Content-Digest")
        if (!digest) {
            throw new GradleException("Failed to resolve digest for ${imageReference}: registry did not return Docker-Content-Digest header")
        }
        return "${repoWithHost}@${digest}"
    }

    /**
     * Return the path where a BuildKit task should write its --metadata-file output.
     * Convention: {buildDir}/buildkit-metadata/{serviceName}{archSuffix}.json
     */
    static File metadataFileFor(Project project, String serviceName, String archSuffix) {
        return project.file("${project.buildDir}/buildkit-metadata/${serviceName}${archSuffix}.json")
    }

    /**
     * Parse a BuildKit --metadata-file JSON output.
     */
    static Map readMetadataFromFile(File metadataFile) {
        if (!metadataFile.exists()) {
            throw new GradleException("BuildKit metadata file not found: ${metadataFile}")
        }
        return (Map) new groovy.json.JsonSlurper().parse(metadataFile)
    }

    /**
     * Read the image digest from a BuildKit --metadata-file JSON output.
     * For single-arch builds, BuildKit may write an OCI index digest that is not directly
     * consumable as a base image. When an architecture suffix is supplied, resolve the
     * platform-specific manifest digest from the pushed tag in the registry instead.
     */
    static String readDigestFromMetadataFile(File metadataFile, String targetArchitecture = "", String imageReferenceOverride = "") {
        def json = readMetadataFromFile(metadataFile)
        def digest = json["containerimage.digest"]
        if (!digest) {
            throw new GradleException("No containerimage.digest found in ${metadataFile}")
        }
        def imageName = imageReferenceOverride ?: json["image.name"]?.toString()
        def descriptorMediaType = json["containerimage.descriptor"]?.get("mediaType")?.toString()
        if (targetArchitecture && imageName && descriptorMediaType in [
                "application/vnd.oci.image.index.v1+json",
                "application/vnd.docker.distribution.manifest.list.v2+json"
        ]) {
            return resolvePlatformDigest(imageName, targetArchitecture)
        }
        return digest.toString()
    }

    /**
     * Resolve a platform-specific manifest digest for an image reference like
     * "host:port/repo:tag" and return only the digest value ("sha256:...").
     */
    static String resolvePlatformDigest(String imageReference, String targetArchitecture) {
        def atIdx = imageReference.indexOf('@')
        def colonIdx = imageReference.lastIndexOf(':')
        def slashIdx = imageReference.lastIndexOf('/')
        String reference = atIdx >= 0
                ? imageReference.substring(atIdx + 1)
                : (colonIdx > slashIdx && colonIdx >= 0 ? imageReference.substring(colonIdx + 1) : "latest")
        String repoWithHost = atIdx >= 0
                ? imageReference.substring(0, atIdx)
                : (colonIdx > slashIdx && colonIdx >= 0 ? imageReference.substring(0, colonIdx) : imageReference)

        def firstSlash = repoWithHost.indexOf('/')
        if (firstSlash < 0) throw new GradleException("Cannot parse registry host from image reference: ${imageReference}")
        def registryHost = repoWithHost.substring(0, firstSlash)
        def repository = repoWithHost.substring(firstSlash + 1)

        def scheme = registryScheme(registryHost)
        def url = new URL("${scheme}://${registryHost}/v2/${repository}/manifests/${reference}")
        def conn = (HttpURLConnection) url.openConnection()
        conn.setRequestMethod("GET")
        conn.setRequestProperty("Accept",
                "application/vnd.oci.image.index.v1+json, " +
                "application/vnd.docker.distribution.manifest.list.v2+json, " +
                "application/vnd.oci.image.manifest.v1+json, " +
                "application/vnd.docker.distribution.manifest.v2+json")
        def authHeader = registryAuthHeader(registryHost)
        if (authHeader) conn.setRequestProperty("Authorization", authHeader)
        conn.setConnectTimeout(5000)
        conn.setReadTimeout(5000)

        if (conn.responseCode != 200) {
            throw new GradleException("Failed to resolve platform digest for ${imageReference}: registry returned HTTP ${conn.responseCode}")
        }
        def responseJson = new groovy.json.JsonSlurper().parse(conn.inputStream)
        def mediaType = responseJson["mediaType"]?.toString()
        if (mediaType in [
                "application/vnd.oci.image.manifest.v1+json",
                "application/vnd.docker.distribution.manifest.v2+json"
        ]) {
            def directDigest = conn.getHeaderField("Docker-Content-Digest")
            if (!directDigest) {
                throw new GradleException("Registry did not return Docker-Content-Digest for ${imageReference}")
            }
            return directDigest
        }
        def matchingManifest = responseJson["manifests"]?.find { manifest ->
            manifest?.platform?.architecture == targetArchitecture && manifest?.platform?.os == "linux"
        }
        def platformDigest = matchingManifest?.digest?.toString()
        if (!platformDigest) {
            throw new GradleException("No ${targetArchitecture}/linux manifest found for ${imageReference}")
        }
        return platformDigest
    }


    // Determine the build mode based on the tasks requested in the CLI, throwing if multiple variants are configured
    private String detectArchitecture(Project rootProject) {
        def startTasks = rootProject.gradle.startParameter.taskNames
        boolean requestingAmd = startTasks.any { it.toLowerCase().endsWith("_amd64") }
        boolean requestingArm = startTasks.any { it.toLowerCase().endsWith("_arm64") }
        boolean requestingMulti = startTasks.any {
            def lower = it.toLowerCase()
            (lower.contains("jib") || lower.contains("buildkit") || lower.contains("buildimages")) &&
                    !lower.endsWith("_amd64") && !lower.endsWith("_arm64")
        }

        if (requestingAmd && requestingArm) throw new GradleException("CONFLICT: Cannot run amd64 and arm64 tasks together.")
        if ((requestingAmd || requestingArm) && requestingMulti) throw new GradleException("CONFLICT: Cannot mix single-arch and multi-arch tasks.")

        if (requestingAmd) return "amd64"
        if (requestingArm) return "arm64"
        return "multi"
    }

    void applyJibConfigurations(Project rootProject, Map<String, Map> projectsToConfigure, Registry finalRegistry, Registry intermediateRegistry, List<Map> buildKitProjects = []) {
        // Build a lookup from imageName -> serviceName for BuildKit-produced intermediate images
        def imageToService = buildKitProjects.collectEntries { [(it.get("imageName").toString()): it.get("serviceName").toString()] }
        // Notice that the jib tasks don't exist yet! BUT - the command line options do, so we can use them
        // to help guide how tasks should be constructed.  jib* tasks are created below, but cannot coexist
        // within a single run!  With multi-architecture support, there would never be a reason to do that.
        def targetArch = detectArchitecture(rootProject)
        println "Configuring Jib builds for architecture: ${targetArch}"

        projectsToConfigure.each { projPath, config ->
            rootProject.configure(rootProject.project(projPath)) { project ->
                def imageName = config.imageName.toString()

                // Create version file task ONCE per project, not per architecture
                if (!project.tasks.findByName("syncVersionFile_${imageName}")) {
                    CommonUtils.syncVersionFileToDockerStaging(project, imageName, "build/versionDir")
                }

                project.plugins.withId('com.google.cloud.tools.jib') {
                    Registry targetReg = config.get("repoName", null) ? finalRegistry : intermediateRegistry
                    def targetFormatter = ImageRegistryFormatterFactory.getFormatter(targetReg.hostUrl)

                    def baseEndpoint = config.get("baseImageRegistryEndpoint", "").toString()
                    def baseImage
                    if (config.containsKey("baseImageOverride")) {
                        // Pre-resolved base image (e.g., from pull-through cache rewriting)
                        baseImage = config.get("baseImageOverride").toString()
                    } else if (baseEndpoint == intermediateRegistry.hostUrl) {
                        baseImage = resolveBaseImage(intermediateRegistry, config.get("baseImageGroup", "").toString(),
                                config.baseImageName.toString(), config.baseImageTag.toString())
                    } else {
                        def formatter = ImageRegistryFormatterFactory.getFormatter(baseEndpoint)
                        baseImage = formatter.getFullBaseImageIdentifier(baseEndpoint, config.get("baseImageGroup", "").toString(),
                                config.baseImageName.toString(), config.baseImageTag.toString())
                    }

                    def (registryDestination, _) = targetFormatter.getFullTargetImageIdentifier(
                            targetReg.hostUrl,
                            config.imageName.toString(),
                            config.imageTag.toString(),
                            config.get("repoName", null)?.toString()
                    )

                    // For intermediate images (built in the same pipeline), resolve the
                    // digest at execution time by reading the producer's --metadata-file output.
                    if (baseEndpoint == intermediateRegistry.hostUrl && !intermediateRegistry.isEcr()) {
                        def producerServiceName = imageToService[config.baseImageName.toString()]
                        if (producerServiceName) {
                            project.tasks.named("jib").configure {
                                doFirst {
                                    def archSuffix = (targetArch != "multi") ? "_${targetArch}" : ""
                                    def metadataFile = RegistryImageBuildUtils.metadataFileFor(rootProject, producerServiceName, archSuffix)
                                    def digest = RegistryImageBuildUtils.readDigestFromMetadataFile(
                                            metadataFile,
                                            targetArch != "multi" ? targetArch : "",
                                            baseImage
                                    )
                                    def colonIdx = baseImage.lastIndexOf(':')
                                    def slashIdx = baseImage.lastIndexOf('/')
                                    def repoWithHost = (colonIdx > slashIdx && colonIdx >= 0) ? baseImage.substring(0, colonIdx) : baseImage
                                    project.jib.from.image = "${repoWithHost}@${digest}"
                                }
                            }
                        }
                    }

                    project.jib {
                        from {
                            image = baseImage
                            platforms {
                                // If multi, add both. If single, add only that one.
                                if (targetArch == "multi" || targetArch == "amd64") platform { architecture = 'amd64'; os = 'linux' }
                                if (targetArch == "multi" || targetArch == "arm64") platform { architecture = 'arm64'; os = 'linux' }
                            }
                        }
                        to {
                            // If single arch, append suffix to image name (e.g. image_amd64:tag)
                            // If multi arch, use base name (e.g. image:tag)
                            def dest = registryDestination
                            if (targetArch != "multi") dest = "${registryDestination}_${targetArch}"
                            image = dest

                            // For single-arch builds, also tag as the base name (without suffix)
                            // to match BuildKit behavior and allow local k8s deployments to find images
                            def tagList = []
                            if (targetArch != "multi") {
                                tagList.add(config.imageTag.toString())
                            }
                            def versionTag = rootProject.findProperty("imageVersion")
                            if (versionTag) {
                                def suffix = (targetArch != "multi") ? "_${targetArch}" : ""
                                def versionDest = targetFormatter.getFullTargetImageIdentifier(
                                        targetReg.hostUrl, config.imageName.toString(), versionTag,
                                        config.get("repoName", null)?.toString())[0]
                                // Extract just the tag portion for Jib's tags list
                                def formattedTag = versionDest.toString().split(":")[-1]
                                tagList.add("${formattedTag}${suffix}".toString())
                            }
                            if (tagList) tags = tagList
                        }
                        extraDirectories {
                            paths {
                                def dockerDir = project.file("docker")
                                if (dockerDir.exists()) {
                                    path { from = dockerDir; into = '/' }
                                }
                                def buildDockerDir = project.file("build/docker")
                                if (buildDockerDir.exists()) {
                                    path { from = buildDockerDir; into = '/' }
                                }
                                path { from = project.file("build/versionDir"); into = '/' }
                            }
                            def extraPerms = (Map<String, String>) config.get("extraPermissions", [:])
                            if (extraPerms) {
                                permissions = extraPerms
                            }
                        }
                        allowInsecureRegistries = true
                        container {
                            def flags = (List<String>) config.get("jvmFlags", [])
                            if (flags) {
                                jvmFlags = flags
                            }
                            // mainClass is auto-detected from the application plugin.
                            // Jib generates: java <jvmFlags> -cp @/app/jib-classpath-file <mainClass>
                        }
                    }

                    // Handle Dependencies
                    def requiredDeps = (Map<String, List<String>>) config.get("requiredDependencies", [:])
                    requiredDeps.each { projectPathStr, taskNames ->
                        def targetProject = projectPathStr ? rootProject.project(projectPathStr) : rootProject
                        taskNames.each { depName ->
                            def resolvedDepName = depName
                            if (depName.startsWith("buildKit_")) {
                                if (isAggregateBakeRequested(rootProject)) {
                                    resolvedDepName = targetArch == "multi" ? "buildKitBakeAll" : "buildKitBakeAll_${targetArch}"
                                } else if (targetArch != "multi") {
                                    resolvedDepName = "${depName}_${targetArch}"
                                }
                            }
                            project.tasks.named("jib").configure {
                                dependsOn("${targetProject.path}:${resolvedDepName}".replace("::", ":"))
                            }
                        }
                    }

                    // Create Alias Tasks - We've already used the task name to deduce the configuration
                    // that we're running with - but we still need to provide both
                    ['amd64', 'arm64'].each { arch ->
                        if (!project.tasks.findByName("jib_${arch}")) {
                            project.tasks.register("jib_${arch}") {
                                group = "docker"
                                description = "Alias for jib (configured via start param)"
                                dependsOn "jib"
                            }
                        }
                    }
                }
            }
        }
    }

    void registerLoginTask(Project project, Registry registry) {
        def isEcr = registry.isEcr()

        if (isEcr) {
            def registryDomain = registry.registryDomain
            def region = (registryDomain =~ /^(\d+)\.dkr\.ecr\.([a-z0-9-]+)\.amazonaws\.com$/)[0][2]
            project.tasks.register("loginToECR", Exec) {
                group = "docker"
                description = "Login to ECR registry ${registryDomain}"
                commandLine 'sh', '-c', "aws ecr get-login-password --region ${region} | docker login --username AWS --password-stdin ${registryDomain}"
            }
            // Bind dependencies...
            project.tasks.matching { it.name.startsWith("buildKit") }.configureEach { dependsOn project.tasks.named("loginToECR") }
            project.subprojects { s -> s.tasks.matching { it.name.endsWith("jib") }.configureEach { dependsOn project.tasks.named("loginToECR") } }
        }
    }

    private void registerBuildKitTasks(Project project, Map cfg, Registry finalRegistry, Registry intermediateRegistry, Map<String, String> imageToService) {
        def versionTag = project.findProperty("imageVersion")?.toString()
        def repoName = cfg.get("repoName")?.toString()

        Registry targetReg = repoName ? finalRegistry : intermediateRegistry
        def registryEndpoint = targetReg.containerUrl

        def builder = project.findProperty("builder") ?: ""
        def builderWasExplicit = true
        if (!builder) {
            try {
                def context = "kubectl config current-context".execute().text.trim()
                if (context) {
                    // NOTE: This naming convention must match the k8s-hosted builder setup scripts.
                    builder = "builder-" + context.replaceAll("[^a-zA-Z0-9_-]", "-")
                    if (!builderWarningShown) {
                        project.logger.lifecycle("No -Pbuilder specified, derived '${builder}' from kube context '${context}'")
                        builderWarningShown = true
                    }
                }
            } catch (Exception ignored) {}
            if (!builder) {
                // Use a placeholder so Gradle configuration succeeds (task registration needs a value).
                // The actual validation happens at task execution time via doFirst below.
                builder = "UNSET"
                builderWasExplicit = false
            }
        }
        def resolvedBuilder = builder
        def builderIsValid = builderWasExplicit
        def imageName = cfg.get("imageName").toString()
        def imageTag = cfg.get("imageTag", "latest").toString()
        def contextPath = project.file(cfg.get("contextDir", ".")).path
        def formatter = ImageRegistryFormatterFactory.getFormatter(registryEndpoint)

        def (primaryDest, cacheDestination) = formatter.getFullTargetImageIdentifier(registryEndpoint, imageName, imageTag, repoName)
        def versionTaggedDest = versionTag ? formatter.getFullTargetImageIdentifier(registryEndpoint, imageName, versionTag, repoName)[0] : null

        // Split build args: intermediate-image args get digest-resolved at execution time
        // by reading the producer's --metadata-file output; all others are static.
        def allBuildArgs = cfg.get("buildArgs", [:]) as Map<String, String>
        def intermediateBaseArgs = allBuildArgs.findAll { key, value ->
            value?.toString()?.contains(intermediateRegistry.containerUrl)
        }
        def staticBuildArgFlags = allBuildArgs.findAll { key, value ->
            !intermediateBaseArgs.containsKey(key)
        }.collect { key, value -> "--build-arg ${key}=${value}" }

        // For each intermediate base-image arg, find the producer service name so we can
        // locate its metadata file at execution time.
        // E.g. "docker-registry:5000/migrations/elasticsearch_test_console:latest"
        //   -> producer service = the one whose imageName is "elasticsearch_test_console"
        def intermediateArgProducers = intermediateBaseArgs.collectEntries { key, tagRef ->
            // Extract the image name from the container-side reference
            // e.g. "docker-registry:5000/migrations/elasticsearch_test_console:latest" -> "elasticsearch_test_console"
            def ref = tagRef.toString()
            def lastSlash = ref.lastIndexOf('/')
            def nameAndTag = lastSlash >= 0 ? ref.substring(lastSlash + 1) : ref
            def colonIdx = nameAndTag.lastIndexOf(':')
            def extractedImageName = colonIdx >= 0 ? nameAndTag.substring(0, colonIdx) : nameAndTag
            // The repo portion before the image name (e.g. "docker-registry:5000/migrations")
            def repoPrefix = lastSlash >= 0 ? ref.substring(0, lastSlash) : ""
            def hostVisibleRef = ref.startsWith(intermediateRegistry.containerUrl)
                    ? "${intermediateRegistry.hostUrl}${ref.substring(intermediateRegistry.containerUrl.length())}"
                    : ref
            [(key): [imageName: extractedImageName, repoPrefix: repoPrefix, hostVisibleRef: hostVisibleRef]]
        }

        // Consumer buildKit_ tasks depend directly on producer buildKit_ tasks.
        // --metadata-file provides synchronous handoff: the producer writes the digest
        // to a local file before exiting, and the consumer reads it in doFirst.
        def resolveDependencies = { task, String archSuffix ->
            cfg.get("requiredDependencies", []).each { depTaskName ->
                if (depTaskName.startsWith("buildKit_")) {
                    task.dependsOn archSuffix ? "${depTaskName}${archSuffix}" : depTaskName
                } else {
                    task.dependsOn depTaskName
                }
            }
        }

        def commonInputs = { task, String archSuffix = "" ->
            task.inputs.dir(project.file(cfg.get("contextDir", ".")))
            task.inputs.property("registryEndpoint", registryEndpoint)
            task.inputs.property("repoName", (repoName ?: "").toString())
            task.inputs.property("imageName", imageName)
            task.inputs.property("imageTag", imageTag)
            task.inputs.property("imageVersion", (versionTag ?: "").toString())
            task.inputs.property("publishStyle", (project.findProperty("publishStyle") ?: "").toString())
            task.outputs.upToDateWhen { false }

            resolveDependencies(task, archSuffix)
        }


        // Helper: resolve intermediate base-image args to digests by reading producer metadata files.
        // archSuffix is used to locate the correct per-architecture metadata file.
        def resolveIntermediateArgs = { String archSuffix ->
            intermediateArgProducers.collect { key, info ->
                def producerServiceName = imageToService[info.imageName]
                if (!producerServiceName) {
                    throw new GradleException("No BuildKit service produces image '${info.imageName}' " +
                            "(needed by ${cfg.serviceName} build arg ${key}). Known images: ${imageToService.keySet()}")
                }
                def metadataFile = RegistryImageBuildUtils.metadataFileFor(project, producerServiceName, archSuffix)
                def arch = archSuffix.startsWith("_") ? archSuffix.substring(1) : archSuffix
                def digest = RegistryImageBuildUtils.readDigestFromMetadataFile(metadataFile, arch, info.hostVisibleRef.toString())
                "--build-arg ${key}=${info.repoPrefix}/${info.imageName}@${digest}"
            }
        }

        def createPlatformTask = { String platform, String suffix ->
            def taskName = "buildKit_${cfg.serviceName}${suffix}"
            def metadataFile = RegistryImageBuildUtils.metadataFileFor(project, cfg.serviceName, suffix)
            if (!project.tasks.findByName(taskName)) {
                project.tasks.register(taskName, Exec) {
                    group = "docker"
                    description = "Build and push ${platform} ${primaryDest}"

                    doFirst {
                        if (!builderIsValid) {
                            throw new GradleException("No -Pbuilder specified and no kube context set. Use -Pbuilder=<name> or set a kube context.")
                        }
                        metadataFile.parentFile.mkdirs()
                        def fullArgs = [
                                "docker buildx build",
                                "--progress=plain",
                                "--platform ${platform}",
                                *(resolvedBuilder ? ["--builder ${resolvedBuilder}"] : []),
                                "-t ${primaryDest}",
                                *(versionTaggedDest ? ["-t", "${versionTaggedDest}"] : []),
                                "--push",
                                "--metadata-file ${metadataFile.absolutePath}",
                                "--cache-to=type=registry,ref=${cacheDestination}${suffix},mode=max,ignore-error=true",
                                "--cache-from=type=registry,ref=${cacheDestination}${suffix}"
                        ]
                        staticBuildArgFlags.each { fullArgs.add(it) }
                        resolveIntermediateArgs(suffix).each { fullArgs.add(it) }
                        fullArgs.add("\"${contextPath}\"")
                        commandLine 'sh', '-c', fullArgs.join(" ")
                    }

                    commonInputs(it, suffix)
                    inputs.property("platform", platform)
                }
            }
        }

        createPlatformTask("linux/amd64", "_amd64")
        createPlatformTask("linux/arm64", "_arm64")

        def multiArchTaskName = "buildKit_${cfg.serviceName}"
        def multiArchMetadataFile = RegistryImageBuildUtils.metadataFileFor(project, cfg.serviceName, "")
        if (!project.tasks.findByName(multiArchTaskName)) {
            project.tasks.register(multiArchTaskName, Exec) {
                doFirst {
                    if (!builderIsValid) {
                        throw new GradleException("No -Pbuilder specified and no kube context set. Use -Pbuilder=<name> or set a kube context.")
                    }
                    multiArchMetadataFile.parentFile.mkdirs()
                    def fullArgs = [
                            "docker buildx build",
                            "--progress=plain",
                            "--platform linux/amd64,linux/arm64",
                            *(resolvedBuilder ? ["--builder ${resolvedBuilder}"] : []),
                            "-t ${primaryDest}",
                            *(versionTaggedDest ? ["-t", "${versionTaggedDest}"] : []),
                            "--push",
                            "--metadata-file ${multiArchMetadataFile.absolutePath}",
                            "--cache-to=type=registry,ref=${cacheDestination},mode=max,ignore-error=true",
                            "--cache-from=type=registry,ref=${cacheDestination}",
                            "--cache-from=type=registry,ref=${cacheDestination}_amd64",
                            "--cache-from=type=registry,ref=${cacheDestination}_arm64"
                    ]
                    staticBuildArgFlags.each { fullArgs.add(it) }
                    resolveIntermediateArgs("").each { fullArgs.add(it) }
                    fullArgs.add("\"${contextPath}\"")
                    commandLine 'sh', '-c', fullArgs.join(" ")
                }
                commonInputs(it, "")
            }
        }
    }

    void applyBuildKitConfigurations(Project rootProject, List<Map> projects, Registry finalRegistry, Registry intermediateRegistry) {
        // Build a lookup from imageName -> serviceName across all BuildKit projects,
        // so consumers can find the correct producer metadata file by image name.
        def imageToService = projects.collectEntries { [(it.get("imageName").toString()): it.get("serviceName").toString()] }
        projects.each { cfg ->
            registerBuildKitTasks(rootProject, cfg, finalRegistry, intermediateRegistry, imageToService)
        }
    }

    void registerBuildKitBakeAggregateTask(Project project,
                                           String taskName,
                                           List<Map> selectedProjects,
                                           List<Map> allProjects,
                                           String architecture,
                                           Registry finalRegistry,
                                           Registry intermediateRegistry) {
        if (project.tasks.findByName(taskName)) {
            return
        }

        def allProjectsByTaskName = allProjects.collectEntries { cfg ->
            [("buildKit_${cfg.serviceName}".toString()): cfg]
        }

        def selectedByServiceName = [:] as LinkedHashMap<String, Map>
        def collectWithDependencies
        collectWithDependencies = { Map cfg ->
            if (!cfg) {
                return
            }
            cfg.get("requiredDependencies", []).each { depTaskName ->
                if (depTaskName.startsWith("buildKit_")) {
                    collectWithDependencies(allProjectsByTaskName[depTaskName])
                }
            }
            selectedByServiceName[cfg.serviceName.toString()] = cfg
        }
        selectedProjects.each { collectWithDependencies(it) }
        def includedProjects = selectedByServiceName.values().toList()

        def includedByServiceName = includedProjects.collectEntries { cfg ->
            [(cfg.serviceName.toString()): cfg]
        }

        def levelByServiceName = [:] as Map<String, Integer>
        def calculateLevel
        calculateLevel = { String serviceName ->
            if (levelByServiceName.containsKey(serviceName)) {
                return levelByServiceName[serviceName]
            }
            def cfg = includedByServiceName[serviceName]
            def buildKitDeps = cfg.get("requiredDependencies", [])
                    .findAll { it.startsWith("buildKit_") }
                    .collect { it.replaceFirst(/^buildKit_/, "") }
                    .findAll { includedByServiceName.containsKey(it) }
            def level = buildKitDeps ? (buildKitDeps.collect { calculateLevel(it) }.max() + 1) : 0
            levelByServiceName[serviceName] = level
            return level
        }

        includedProjects.each { cfg -> calculateLevel(cfg.serviceName.toString()) }

        def projectsByLevel = includedProjects.groupBy { cfg -> levelByServiceName[cfg.serviceName.toString()] }
        def formatterByRegistry = [:] as Map<String, Object>
        def builder = project.findProperty("builder") ?: ""
        def builderWasExplicit = true
        if (!builder) {
            try {
                def context = "kubectl config current-context".execute().text.trim()
                if (context) {
                    builder = "builder-" + context.replaceAll("[^a-zA-Z0-9_-]", "-")
                    if (!builderWarningShown) {
                        project.logger.lifecycle("No -Pbuilder specified, derived '${builder}' from kube context '${context}'")
                        builderWarningShown = true
                    }
                }
            } catch (Exception ignored) {}
            if (!builder) {
                builder = "UNSET"
                builderWasExplicit = false
            }
        }

        project.tasks.register(taskName, Exec) { task ->
            group = "docker"
            description = "Build BuildKit images in dependency-ordered parallel phases using buildx bake (${architecture})"

            doFirst {
                if (!builderWasExplicit) {
                    throw new GradleException("No -Pbuilder specified and no kube context set. Use -Pbuilder=<name> or set a kube context.")
                }

                def bakeDefinition = [group: [:], target: [:]]
                def phaseNames = []

                projectsByLevel.keySet().sort().each { level ->
                    def phaseName = "phase${level}"
                    phaseNames << phaseName
                    def targetsForPhase = []
                    projectsByLevel[level].sort { a, b -> a.serviceName.toString() <=> b.serviceName.toString() }.each { cfg ->
                        def repoName = cfg.get("repoName")?.toString()
                        Registry targetRegistry = repoName ? finalRegistry : intermediateRegistry
                        def registryEndpoint = targetRegistry.containerUrl
                        def formatter = formatterByRegistry.computeIfAbsent(registryEndpoint) {
                            ImageRegistryFormatterFactory.getFormatter(registryEndpoint)
                        }
                        def imageName = cfg.get("imageName").toString()
                        def imageTag = cfg.get("imageTag", "latest").toString()
                        def versionTag = project.findProperty("imageVersion")?.toString()
                        def (primaryDest, cacheDestination) = formatter.getFullTargetImageIdentifier(registryEndpoint, imageName, imageTag, repoName)
                        def versionTaggedDest = versionTag ? formatter.getFullTargetImageIdentifier(registryEndpoint, imageName, versionTag, repoName)[0] : null
                        def buildArgs = cfg.get("buildArgs", [:]).collectEntries { key, value ->
                            [(key.toString()): value.toString()]
                        }

                        def cacheSuffix = architecture == "multi" ? "" : "_${architecture}"
                        def cacheFrom = ["type=registry,ref=${cacheDestination}${cacheSuffix}".toString()]
                        if (architecture == "multi") {
                            cacheFrom.add("type=registry,ref=${cacheDestination}_amd64".toString())
                            cacheFrom.add("type=registry,ref=${cacheDestination}_arm64".toString())
                        }

                        def targetName = cfg.serviceName.toString()
                        targetsForPhase << targetName
                        bakeDefinition.target[targetName] = [
                                context     : project.file(cfg.get("contextDir", ".")).absolutePath,
                                dockerfile  : "Dockerfile",
                                tags        : ([primaryDest] + (versionTaggedDest ? [versionTaggedDest] : [])),
                                args        : buildArgs,
                                platforms   : architecture == "multi" ? ["linux/amd64", "linux/arm64"] : ["linux/${architecture}".toString()],
                                "cache-from": cacheFrom,
                                "cache-to"  : ["type=registry,ref=${cacheDestination}${cacheSuffix},mode=max,ignore-error=true".toString()]
                        ]
                    }
                    bakeDefinition.group[phaseName] = [targets: targetsForPhase]
                }

                def outputFile = project.layout.buildDirectory.file("docker-bake/${taskName}.json").get().asFile
                outputFile.parentFile.mkdirs()
                outputFile.text = JsonOutput.prettyPrint(JsonOutput.toJson(bakeDefinition))

                def commands = phaseNames.collect { phaseName ->
                    def args = [
                            "docker buildx bake",
                            "--progress=plain",
                            "-f \"${outputFile.absolutePath}\"",
                            "--builder ${builder}",
                            "--push",
                            phaseName
                    ]
                    args.join(" ")
                }
                commandLine 'sh', '-c', commands.join(" && ")
            }

            doLast {
                def archSuffix = architecture == "multi" ? "" : "_${architecture}"
                includedProjects.each { cfg ->
                    def repoName = cfg.get("repoName")?.toString()
                    Registry targetRegistry = repoName ? finalRegistry : intermediateRegistry
                    def hostFormatter = ImageRegistryFormatterFactory.getFormatter(targetRegistry.hostUrl)
                    def imageName = cfg.get("imageName").toString()
                    def imageTag = cfg.get("imageTag", "latest").toString()
                    def hostVisibleRef = hostFormatter.getFullTargetImageIdentifier(
                            targetRegistry.hostUrl,
                            imageName,
                            imageTag,
                            repoName
                    )[0].toString()
                    def digest = architecture == "multi"
                            ? resolveDigest(hostVisibleRef)?.split('@')?.last()
                            : resolvePlatformDigest(hostVisibleRef, architecture)
                    def metadataFile = RegistryImageBuildUtils.metadataFileFor(project, cfg.serviceName.toString(), archSuffix)
                    metadataFile.parentFile.mkdirs()
                    metadataFile.text = JsonOutput.prettyPrint(JsonOutput.toJson([
                            "image.name"           : hostVisibleRef,
                            "containerimage.digest": digest
                    ]))
                }
            }

            includedProjects.each { cfg ->
                inputs.dir(project.file(cfg.get("contextDir", ".")))
                inputs.property("${cfg.serviceName}.contextDir".toString(), cfg.get("contextDir", ".").toString())
                inputs.property("${cfg.serviceName}.imageName".toString(), cfg.get("imageName").toString())
                inputs.property("${cfg.serviceName}.imageTag".toString(), cfg.get("imageTag", "latest").toString())
                inputs.property("${cfg.serviceName}.buildArgs".toString(), cfg.get("buildArgs", [:]).collectEntries { key, value ->
                    [(key.toString()): value.toString()]
                })

                cfg.get("requiredDependencies", []).findAll { !it.startsWith("buildKit_") }.each { depTaskName ->
                    dependsOn depTaskName
                }
            }

            inputs.property("architecture", architecture)
            inputs.property("imageVersion", (project.findProperty("imageVersion") ?: "").toString())
            inputs.property("publishStyle", (project.findProperty("publishStyle") ?: "").toString())
            outputs.upToDateWhen { false }
        }
    }
}
