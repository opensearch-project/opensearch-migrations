package org.opensearch.migrations.image

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.opensearch.migrations.common.CommonUtils

class RegistryImageBuildUtils {

    // Determine the build mode based on the tasks requested in the CLI, throwing if multiple variants are configured
    private String detectArchitecture(Project rootProject) {
        def startTasks = rootProject.gradle.startParameter.taskNames

        boolean requestingAmd = false
        boolean requestingArm = false
        boolean requestingMulti = false

        startTasks.each { taskPath ->
            // We care about the task name, which is the last part of a path like :project:task
            def taskName = taskPath.split(':').last().toLowerCase()

            // Only analyze tasks relevant to image building
            // We skip generic tasks like 'clean', 'test', 'classes', etc.
            boolean isImageTask = taskName.contains("jib") ||
                    taskName.contains("buildkit") ||
                    taskName.contains("buildimages")

            if (isImageTask) {
                if (taskName.endsWith("amd64")) {
                    requestingAmd = true
                } else if (taskName.endsWith("arm64")) {
                    requestingArm = true
                } else {
                    // It is an image task but lacks a specific architecture suffix
                    // Therefore, it implies a multi-arch intent (e.g., 'jib', 'jibAll')
                    requestingMulti = true
                }
            }
        }

        if (requestingAmd && requestingArm) {
            throw new GradleException(
                    "CONFLICT: You cannot run both 'amd64' and 'arm64' tasks in the same build.\n" +
                            "Please run them separately or use a multi-arch task (e.g., 'jibAll')."
            )
        } else if ((requestingAmd || requestingArm) && requestingMulti) {
            throw new GradleException(
                    "CONFLICT: You cannot mix specific architecture tasks (ending in _amd64/_arm64) " +
                            "with generic multi-arch tasks (like 'jib', 'jibAll', or 'buildImagesToRegistry') in the same run.\n" +
                            "This prevents accidental publishing of single-arch images to multi-arch tags."
            )
        }

        if (requestingAmd) return "amd64"
        if (requestingArm) return "arm64"

        return "multi" // Default
    }

    void applyJibConfigurations(Project rootProject, Map<String, Map> projectsToConfigure) {
        // Notice that the jib tasks don't exist yet! BUT - the command line options do, so we can use them
        // to help guide how tasks should be constructed.  jib* tasks are created below, but cannot coexist
        // within a single run!  With multi-architecture support, there would never be a reason to do that.
        def targetArch = detectArchitecture(rootProject)
        println "Configuring Jib builds for architecture: ${targetArch}"

        projectsToConfigure.each { projPath, config ->
            rootProject.configure(rootProject.project(projPath)) { project ->
                def imageName = config.imageName.toString()

                // Create version file task ONCE per project, not per architecture
                if (!project.tasks.findByName("copyVersionFile_${imageName}")) {
                    CommonUtils.copyVersionFileToDockerStaging(project, imageName, "build/versionDir")
                }

                project.plugins.withId('com.google.cloud.tools.jib') {
                    def registryEndpoint = rootProject.ext.registryEndpoint.toString()
                    def baseFormatter = ImageRegistryFormatterFactory.getFormatter(config.get("baseImageRegistryEndpoint", "").toString())
                    def targetFormatter = ImageRegistryFormatterFactory.getFormatter(registryEndpoint)

                    def baseImage = baseFormatter.getFullBaseImageIdentifier(
                            config.get("baseImageRegistryEndpoint", "").toString(),
                            config.get("baseImageGroup", "").toString(),
                            config.baseImageName.toString(),
                            config.baseImageTag.toString()
                    )

                    def (registryDestination, _) = targetFormatter.getFullTargetImageIdentifier(
                            registryEndpoint,
                            config.imageName.toString(),
                            config.imageTag.toString(),
                            config.get("repoName", null)?.toString()
                    )

                    // Configure the ONE standard 'jib' extension based on our detected mode
                    project.jib {
                        from {
                            image = baseImage
                            platforms {
                                // If multi, add both. If single, add only that one.
                                if (targetArch == "multi" || targetArch == "amd64") {
                                    platform { architecture = 'amd64'; os = 'linux' }
                                }
                                if (targetArch == "multi" || targetArch == "arm64") {
                                    platform { architecture = 'arm64'; os = 'linux' }
                                }
                            }
                        }
                        to {
                            // If single arch, append suffix to image name (e.g. image_amd64:tag)
                            // If multi arch, use base name (e.g. image:tag)
                            def dest = registryDestination
                            if (targetArch != "multi") {
                                dest = "${registryDestination}_${targetArch}"
                            }
                            image = dest

                            def versionTag = rootProject.findProperty("imageVersion")
                            if (versionTag) {
                                def suffix = (targetArch != "multi") ? "_${targetArch}" : ""
                                tags = ["${versionTag}${suffix}".toString()]
                            }
                        }
                        extraDirectories {
                            paths {
                                path { from = project.file("docker"); into = '/' }
                                path { from = project.file("build/versionDir"); into = '/' }
                            }
                            permissions = ['/runJavaWithClasspath.sh': '755']
                        }
                        allowInsecureRegistries = true
                        container {
                            entrypoint = ['tail', '-f', '/dev/null']
                        }
                    }

                    // Handle Dependencies
                    def requiredDeps = (Map<String, List<String>>) config.get("requiredDependencies", [:])
                    requiredDeps.each { projectPathStr, taskNames ->
                        def targetProject = projectPathStr ? rootProject.project(projectPathStr) : rootProject
                        taskNames.each { depName ->
                            // If dependency is BuildKit, append suffix if we are in single-arch mode
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
                    if (!project.tasks.findByName("jib_amd64")) {
                        project.tasks.register("jib_amd64") {
                            group = "docker"
                            description = "Alias for jib (configured via start param)"
                            dependsOn "jib"
                        }
                    }
                    if (!project.tasks.findByName("jib_arm64")) {
                        project.tasks.register("jib_arm64") {
                            group = "docker"
                            description = "Alias for jib (configured via start param)"
                            dependsOn "jib"
                        }
                    }
                }
            }
        }
    }

    void registerLoginTask(Project project) {
        def registryEndpoint = project.ext.registryEndpoint.toString()
        def registryDomain = registryEndpoint.split("/")[0]
        def isEcr = registryDomain.contains(".ecr.") && registryDomain.contains(".amazonaws.com")
        if (isEcr) {
            def region
            def matcher = (registryDomain =~ /^(\d+)\.dkr\.ecr\.([a-z0-9-]+)\.amazonaws\.com$/)
            if (matcher.matches()) {
                region = matcher[0][2]
            } else {
                throw new GradleException("Could not extract region from ECR registry endpoint: ${registryDomain}")
            }

            project.tasks.register("loginToECR", Exec) {
                group = "docker"
                description = "Login to ECR registry ${registryDomain}"
                commandLine 'sh', '-c', """
                    aws ecr get-login-password --region ${region} | \
                    docker login --username AWS --password-stdin ${registryDomain}
                """.stripIndent()
            }

            project.tasks.matching { it.name.startsWith("buildKit") }.configureEach {
                dependsOn project.tasks.named("loginToECR")
            }
            project.subprojects { subproject ->
                subproject.tasks.matching { it.name.endsWith("jib") }.configureEach {
                    dependsOn project.tasks.named("loginToECR")
                }
            }
        }
    }

    void registerBuildKitTasks(Project project, Map cfg) {
        def versionTag = project.findProperty("imageVersion")?.toString()
        def registryEndpoint = project.ext.buildKitRegistryEndpoint.toString()
        def builder = project.findProperty("builder") ?: "local-remote-builder"
        def imageName = cfg.get("imageName").toString()
        def imageTag = cfg.get("imageTag", "latest").toString()
        def contextDir = cfg.get("contextDir", ".")
        def serviceName = cfg.get("serviceName")
        def contextFile = project.file(contextDir)
        def contextPath = contextFile.path
        def formatter = ImageRegistryFormatterFactory.getFormatter(registryEndpoint)
        def repoName = cfg.get("repoName")?.toString()

        def (primaryDest, cacheDestination) =
        formatter.getFullTargetImageIdentifier(registryEndpoint, imageName, imageTag, repoName)

        def buildArgFlags = cfg.get("buildArgs", [:]).collect { key, value ->
            "--build-arg ${key}=${value}"
        }

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
            task.inputs.dir(contextFile)
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
            def taskName = "buildKit_${serviceName}${suffix}"
            if (!project.tasks.findByName(taskName)) {
                project.tasks.register(taskName, Exec) {
                    group = "docker"
                    description = "Build and push ${platform} ${primaryDest}"

                    commonInputs(it, suffix)
                    inputs.property("platform", platform)

                    def tagFlags = ["-t ${primaryDest}${suffix}"]
                    if (repoName && versionTag) {
                        def sanitizedVersion = versionTag.replaceAll('[^A-Za-z0-9_.-]', '-')
                        def versionedDest = primaryDest.replace(":${imageTag}", ":${sanitizedVersion}${suffix}")
                        tagFlags.add("-t ${versionedDest}")
                    }

                    def fullArgs = [
                            "docker buildx build",
                            "--platform ${platform}",
                            "--builder ${builder}",
                            *tagFlags,
                            "--push",
                            "--cache-to=type=registry,ref=${cacheDestination}${suffix},mode=max",
                            "--cache-from=type=registry,ref=${cacheDestination}${suffix}"
                    ]
                    buildArgFlags.each { fullArgs.add(it) }
                    fullArgs.add(contextPath)
                    def buildCommand = fullArgs.join(" ")

                    doFirst {
                        println "Building ${platform} image"
                        println "Executing buildx command: ${buildCommand}"
                    }

                    commandLine 'sh', '-c', buildCommand
                }
            }
        }

        // Create platform-specific tasks
        createPlatformTask("linux/amd64", "_amd64")
        createPlatformTask("linux/arm64", "_arm64")

        // Create multi-arch task
        def multiArchTaskName = "buildKit_${serviceName}"
        if (!project.tasks.findByName(multiArchTaskName)) {
            project.tasks.register(multiArchTaskName, Exec) {
                group = "docker"
                description = "Build and push multi-arch ${primaryDest} (amd64 + arm64)"

                // Pass empty string for archSuffix - this means dependencies won't get suffixes
                commonInputs(it, "")

                def tagFlags = ["-t ${primaryDest}"]
                if (repoName && versionTag) {
                    def sanitizedVersion = versionTag.replaceAll('[^A-Za-z0-9_.-]', '-')
                    def versionedDest = primaryDest.replace(":${imageTag}", ":${sanitizedVersion}")
                    tagFlags.add("-t ${versionedDest}")
                }

                def fullArgs = [
                        "docker buildx build",
                        "--platform linux/amd64,linux/arm64",
                        "--builder ${builder}",
                        *tagFlags,
                        "--push",
                        "--cache-to=type=registry,ref=${cacheDestination},mode=max",
                        "--cache-from=type=registry,ref=${cacheDestination}",
                        "--cache-from=type=registry,ref=${cacheDestination}_amd64",
                        "--cache-from=type=registry,ref=${cacheDestination}_arm64"
                ]
                buildArgFlags.each { fullArgs.add(it) }
                fullArgs.add(contextPath)
                def buildCommand = fullArgs.join(" ")

                doFirst {
                    println "Building multi-arch image for linux/amd64,linux/arm64"
                    println "Executing buildx command: ${buildCommand}"
                }

                commandLine 'sh', '-c', buildCommand
            }
        }
    }

    void applyBuildKitConfigurations(Project rootProject, List<Map> projects) {
        projects.each { cfg ->
            registerBuildKitTasks(rootProject, cfg)
        }
    }
}
