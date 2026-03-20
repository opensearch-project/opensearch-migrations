package org.opensearch.migrations.image

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.opensearch.migrations.common.CommonUtils

class RegistryImageBuildUtils {
    private static boolean builderWarningShown = false

    static class Registry { // Helper object
        final String hostUrl      // For Jib, which runs in the JVM directly (e.g., localhost:5001)
        final String containerUrl // For BuildKit, which runs in a container (e.g., docker-registry:5000)

        Registry(String rawUrl) {
            this.hostUrl = rawUrl
            // Assume that to the container that anything on localhost should map to docker-registry:5000
            if (rawUrl.startsWith("localhost:")) {
                this.containerUrl = "docker-registry:5000"
            } else {
                this.containerUrl = rawUrl
            }
        }

        String getRegistryDomain() { hostUrl.split('/')[0] }
        boolean isEcr() { registryDomain.contains('.ecr.') && registryDomain.contains('.amazonaws.com') }
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
     * Query a v2 registry for the digest of an image reference like "host:port/repo:tag"
     * and return "host:port/repo@sha256:...". Throws if the digest cannot be resolved.
     */
    static String resolveDigest(String imageReference, boolean allowInsecure) {
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

        def scheme = allowInsecure ? "http" : "https"
        def url = new URL("${scheme}://${registryHost}/v2/${repository}/manifests/${tag}")
        def conn = (HttpURLConnection) url.openConnection()
        conn.setRequestMethod("HEAD")
        conn.setRequestProperty("Accept",
                "application/vnd.docker.distribution.manifest.list.v2+json, " +
                "application/vnd.docker.distribution.manifest.v2+json, " +
                "application/vnd.oci.image.index.v1+json, " +
                "application/vnd.oci.image.manifest.v1+json")
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

    void applyJibConfigurations(Project rootProject, Map<String, Map> projectsToConfigure, Registry finalRegistry, Registry intermediateRegistry) {
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
                    // digest at execution time to ensure reproducible builds.
                    if (baseEndpoint == intermediateRegistry.hostUrl && !intermediateRegistry.isEcr()) {
                        project.tasks.named("jib").configure {
                            doFirst {
                                project.jib.from.image = RegistryImageBuildUtils.resolveDigest(baseImage, project.jib.allowInsecureRegistries)
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
                            if (depName.startsWith("buildKit_") && targetArch != "multi") {
                                resolvedDepName = "${depName}_${targetArch}"
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

    private void registerBuildKitTasks(Project project, Map cfg, Registry finalRegistry, Registry intermediateRegistry) {
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
                    // NOTE: This naming convention must match setupK8sBuilders.sh's BUILDER_NAME derivation
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

        def buildArgFlags = cfg.get("buildArgs", [:]).collect { key, value -> "--build-arg ${key}=${value}" }

        // Helper to resolve dependencies with architecture suffix
        // archSuffix is empty for multi-arch task, non-empty for single-arch tasks
        def resolveDependencies = { task, String archSuffix ->
            cfg.get("requiredDependencies", []).each { depTaskName ->
                if (depTaskName.startsWith("buildKit_")) {
                    // For buildKit dependencies:
                    // - Single-arch tasks depend on matching single-arch dependency
                    // - Multi-arch task depends on multi-arch dependency (no suffix)
                    task.dependsOn archSuffix ? "${depTaskName}${archSuffix}" : depTaskName
                } else {
                    // Non-buildKit dependencies are always the same
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

        def createPlatformTask = { String platform, String suffix ->
            def taskName = "buildKit_${cfg.serviceName}${suffix}"
            if (!project.tasks.findByName(taskName)) {
                project.tasks.register(taskName, Exec) {
                    group = "docker"
                    description = "Build and push ${platform} ${primaryDest}"

                    doFirst {
                        if (!builderIsValid) {
                            throw new GradleException("No -Pbuilder specified and no kube context set. Use -Pbuilder=<name> or set a kube context.")
                        }
                    }

                    commonInputs(it, suffix)
                    inputs.property("platform", platform)

                    // Use primaryDest and cacheDestination which were calculated using registryEndpoint (Container View)
                    def fullArgs = [
                            "docker buildx build",
                            "--progress=plain",
                            "--platform ${platform}",
                            *(builder ? ["--builder ${builder}"] : []),
                            // don't include the suffix - this is dangerous, but single-platform builds are supported
                            // as a convenience to developers that are purposefully ONLY supporting PART of the
                            // potential architectures
                            "-t ${primaryDest}",
                            *(versionTaggedDest ? ["-t", "${versionTaggedDest}"] : []),
                            "--push",
                            "--cache-to=type=registry,ref=${cacheDestination}${suffix},mode=max",
                            "--cache-from=type=registry,ref=${cacheDestination}${suffix}"
                    ]
                    buildArgFlags.each { fullArgs.add(it) }
                    fullArgs.add("\"${contextPath}\"")
                    commandLine 'sh', '-c', fullArgs.join(" ")
                }
            }
        }

        createPlatformTask("linux/amd64", "_amd64")
        createPlatformTask("linux/arm64", "_arm64")

        def multiArchTaskName = "buildKit_${cfg.serviceName}"
        if (!project.tasks.findByName(multiArchTaskName)) {
            project.tasks.register(multiArchTaskName, Exec) {
                doFirst {
                    if (!builderIsValid) {
                        throw new GradleException("No -Pbuilder specified and no kube context set. Use -Pbuilder=<name> or set a kube context.")
                    }
                }
                commonInputs(it, "")
                def fullArgs = [
                        "docker buildx build",
                        "--progress=plain",
                        "--platform linux/amd64,linux/arm64",
                        *(builder ? ["--builder ${builder}"] : []),
                        "-t ${primaryDest}",
                        *(versionTaggedDest ? ["-t", "${versionTaggedDest}"] : []),
                        "--push",
                        "--cache-to=type=registry,ref=${cacheDestination},mode=max",
                        "--cache-from=type=registry,ref=${cacheDestination}",
                        "--cache-from=type=registry,ref=${cacheDestination}_amd64",
                        "--cache-from=type=registry,ref=${cacheDestination}_arm64"
                ]
                buildArgFlags.each { fullArgs.add(it) }
                fullArgs.add("\"${contextPath}\"")
                commandLine 'sh', '-c', fullArgs.join(" ")
            }
        }
    }

    void applyBuildKitConfigurations(Project rootProject, List<Map> projects, Registry finalRegistry, Registry intermediateRegistry) {
        projects.each { cfg ->
            registerBuildKitTasks(rootProject, cfg, finalRegistry, intermediateRegistry)
        }
    }
}
