package org.opensearch.migrations.image

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.opensearch.migrations.common.CommonUtils

class RegistryImageBuildUtils {

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
        def formatter = ImageRegistryFormatterFactory.getFormatter(registry.containerUrl)
        return formatter.getFullBaseImageIdentifier(registry.containerUrl, group, image, tag)
    }

    /**
     * Build a list of candidate base images to try, in priority order.
     * If intermediateRegistry is ECR, the ECR mirrored path comes first.
     * Then the configured endpoint (e.g. docker.io), then fallback mirrors.
     */
    static List<String> buildBaseImageCandidates(Registry intermediateRegistry, String endpoint, String group, String name, String tag) {
        def candidates = []
        // ECR mirrored paths first (if available)
        if (intermediateRegistry.isEcr()) {
            candidates << "${intermediateRegistry.registryDomain}/mirrored/${endpoint}/${group}/${name}:${tag}"
            // mirrorToEcr.sh uses mirror.gcr.io as the manifest source, so also try that path
            if (endpoint == "docker.io") {
                candidates << "${intermediateRegistry.registryDomain}/mirrored/mirror.gcr.io/${group}/${name}:${tag}"
            }
        }
        // Configured endpoint (e.g. docker.io)
        def formatter = ImageRegistryFormatterFactory.getFormatter(endpoint)
        candidates << formatter.getFullBaseImageIdentifier(endpoint, group, name, tag)
        // Fallback mirrors for docker.io images
        if (endpoint == "docker.io") {
            candidates << "mirror.gcr.io/${group}/${name}:${tag}"
            candidates << "public.ecr.aws/docker/${group}/${name}:${tag}"
        }
        return candidates.unique()
    }

    /**
     * Probe a registry image to check if it exists using crane.
     */
    static boolean probeImage(String image) {
        try {
            def proc = ["crane", "manifest", image].execute()
            proc.waitForOrKill(15000)
            return proc.exitValue() == 0
        } catch (Exception e) {
            return false
        }
    }

    /**
     * Resolve the base image by trying each candidate in order.
     * Returns the first accessible image, or the first candidate as fallback.
     * If the base image endpoint matches the intermediate registry (i.e. it's a locally-built image),
     * skip mirror resolution entirely — just use the direct registry path.
     */
    static String resolveBaseImageFromMirrors(Registry intermediateRegistry, String endpoint, String group, String name, String tag) {
        // If the base image is already on the intermediate registry (e.g. captureProxyBase built by buildKit),
        // don't apply mirror resolution — just resolve it directly.
        if (endpoint == intermediateRegistry.hostUrl) {
            def directImage = resolveBaseImage(intermediateRegistry, group, name, tag)
            println "Base image is on intermediate registry, skipping mirror resolution: ${directImage}"
            return directImage
        }
        def candidates = buildBaseImageCandidates(intermediateRegistry, endpoint, group, name, tag)
        println "Resolving base image from mirrors: ${candidates}"
        for (candidate in candidates) {
            if (probeImage(candidate)) {
                println "  ✅ Using base image: ${candidate}"
                return candidate
            }
            println "  ❌ Not available: ${candidate}"
        }
        // Fallback to first candidate — Jib will fail with a clear error
        println "  ⚠️ No mirror responded, falling back to: ${candidates[0]}"
        return candidates[0]
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

                    def baseImage = resolveBaseImageFromMirrors(
                            intermediateRegistry,
                            config.get("baseImageRegistryEndpoint", "").toString(),
                            config.get("baseImageGroup", "").toString(),
                            config.baseImageName.toString(),
                            config.baseImageTag.toString()
                        )

                    def (registryDestination, _) = targetFormatter.getFullTargetImageIdentifier(
                            targetReg.hostUrl,
                            config.imageName.toString(),
                            config.imageTag.toString(),
                            config.get("repoName", null)?.toString()
                    )

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

                            def versionTag = rootProject.findProperty("imageVersion")
                            if (versionTag) {
                                def suffix = (targetArch != "multi") ? "_${targetArch}" : ""
                                def versionDest = targetFormatter.getFullTargetImageIdentifier(
                                        targetReg.hostUrl, config.imageName.toString(), versionTag,
                                        config.get("repoName", null)?.toString())[0]
                                // Extract just the tag portion for Jib's tags list
                                def formattedTag = versionDest.toString().split(":")[-1]
                                tags = ["${formattedTag}${suffix}".toString()]
                            }
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
                        container { entrypoint = ['tail', '-f', '/dev/null'] }
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

        def builder = project.findProperty("builder") ?: "local-remote-builder"
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

                    commonInputs(it, suffix)
                    inputs.property("platform", platform)

                    // Use primaryDest and cacheDestination which were calculated using registryEndpoint (Container View)
                    def fullArgs = [
                            "docker buildx build",
                            "--progress=plain",
                            "--platform ${platform}",
                            "--builder ${builder}",
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
                    fullArgs.add(contextPath)
                    commandLine 'sh', '-c', fullArgs.join(" ")
                }
            }
        }

        createPlatformTask("linux/amd64", "_amd64")
        createPlatformTask("linux/arm64", "_arm64")

        def multiArchTaskName = "buildKit_${cfg.serviceName}"
        if (!project.tasks.findByName(multiArchTaskName)) {
            project.tasks.register(multiArchTaskName, Exec) {
                commonInputs(it, "")
                def fullArgs = [
                        "docker buildx build",
                        "--progress=plain",
                        "--platform linux/amd64,linux/arm64",
                        "--builder ${builder}",
                        "-t ${primaryDest}",
                        *(versionTaggedDest ? ["-t", "${versionTaggedDest}"] : []),
                        "--push",
                        "--cache-to=type=registry,ref=${cacheDestination},mode=max",
                        "--cache-from=type=registry,ref=${cacheDestination}",
                        "--cache-from=type=registry,ref=${cacheDestination}_amd64",
                        "--cache-from=type=registry,ref=${cacheDestination}_arm64"
                ]
                buildArgFlags.each { fullArgs.add(it) }
                fullArgs.add(contextPath)
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
