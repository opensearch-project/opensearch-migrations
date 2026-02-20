package org.opensearch.migrations.common

import org.gradle.api.GradleException
import org.gradle.api.Task
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.Project
import com.bmuschko.gradle.docker.tasks.image.Dockerfile
import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage

class CommonUtils {

    /**
     * Adds a dependency on a DockerBuildImage task and tracks its imageIdFile as an input.
     * This ensures that when the base image is rebuilt, the dependent task will also rebuild.
     * The input property name is auto-generated from the base image task name.
     *
     * @param task The task that depends on the Docker image
     * @param baseImageTaskPath The full task path (e.g., ":TrafficCapture:dockerSolution:buildDockerImage_elasticsearch_client_test_console")
     */
    static void dependOnDockerImage(Task task, String baseImageTaskPath) {
        def rootProject = task.project.rootProject
        // Parse the task path to get project path and task name
        def lastColonIndex = baseImageTaskPath.lastIndexOf(':')
        def projectPath = baseImageTaskPath.substring(0, lastColonIndex)
        def taskName = baseImageTaskPath.substring(lastColonIndex + 1)
        def targetProject = projectPath.isEmpty() ? rootProject : rootProject.project(projectPath)
        def baseImageTaskProvider = targetProject.tasks.named(taskName, DockerBuildImage)
        dependOnDockerImage(task, baseImageTaskProvider)
    }

    /**
     * Adds a dependency on a DockerBuildImage task and tracks its imageIdFile as an input.
     * This ensures that when the base image is rebuilt, the dependent task will also rebuild.
     *
     * @param task The task that depends on the Docker image
     * @param baseImageTaskProvider The TaskProvider for the DockerBuildImage task to depend on
     */
    static void dependOnDockerImage(Task task, TaskProvider<DockerBuildImage> baseImageTaskProvider) {
        def logger = task.project.logger
        def baseTaskName = baseImageTaskProvider.name

        task.dependsOn(baseImageTaskProvider)
        // Use map to get the imageIdFile Provider for configuration cache compatibility
        def imageIdFileProvider = baseImageTaskProvider.map { it.imageIdFile.get().asFile }
        task.inputs.file(imageIdFileProvider).withPropertyName(baseTaskName)

        // Add doFirst action to log the actual image ID at execution time
        // Capture only serializable values (not the TaskProvider) for configuration cache compatibility
        def taskPath = task.path
        task.doFirst {
            // Access the imageIdFile through the task's inputs to avoid capturing the TaskProvider
            def imageIdFile = imageIdFileProvider.get()
            if (imageIdFile.exists()) {
                def imageId = imageIdFile.text.trim()
                logger.lifecycle("Docker image dependency: {} depends on {} with imageId: {}",
                        taskPath, baseTaskName, imageId)
                logger.debug("  imageIdFile path: {}", imageIdFile.absolutePath)
            } else {
                logger.warn("Docker image dependency: {} depends on {} but imageIdFile does not exist: {}",
                        taskPath, baseTaskName, imageIdFile.absolutePath)
            }
        }
    }

    static def calculateDockerHash(def files) {
        def MessageDigest = java.security.MessageDigest
        def digest = MessageDigest.getInstance('SHA-256')
        files.each { file ->
                    file.withInputStream { is ->
                        byte[] buffer = new byte[1024]
                        int read
                        while ((read = is.read(buffer)) != -1) {
                            digest.update(buffer, 0, read)
                        }
                    }
                }
        return digest.digest().encodeHex().toString()
    }

    static def copyArtifactFromProjectToProjectsDockerStaging(Project dockerBuildProject, Project project,
                                                              String dockerImageName) {
        def destBuildDir = "build/docker/${dockerImageName}_${project.name}"
        def destDir = "${destBuildDir}"
        copyArtifactFromProjectToProjectsDockerStaging(dockerBuildProject, project, dockerImageName, destDir)
    }
    static def copyArtifactFromProjectToProjectsDockerStaging(Project dockerBuildProject, Project sourceArtifactProject,
                                                              String destProjectName, String destDir) {
        // Sync performs a copy, while also deleting items from the destination directory that are not in the source directory
        // In our case, jars of old versions were getting "stuck" and causing conflicts when the program was run
        // Using tasks.register() for lazy task configuration (configuration avoidance)
        return dockerBuildProject.tasks.register("copyArtifact_${destProjectName}", Sync) {
            into destDir
            duplicatesStrategy = 'WARN'

            // Copy VERSION file
            from(dockerBuildProject.rootProject.layout.projectDirectory.file("VERSION"))

            // Copy jars
            // Lazily evaluate this runtimeClasspath
            from(dockerBuildProject.provider { sourceArtifactProject.configurations.runtimeClasspath }) {
                include "*.jar"
                into("jars")
            }
            // Use map to get output files instead of task reference for configuration cache compatibility
            from(sourceArtifactProject.tasks.named('jar').map { it.outputs.files }) {
                include "*.jar"
                into("jars")
            }
            
            // Explicit inputs/outputs for incremental build support
            inputs.file(dockerBuildProject.rootProject.layout.projectDirectory.file("VERSION"))
            inputs.files(dockerBuildProject.provider { sourceArtifactProject.configurations.runtimeClasspath })
            // Use map to get output files instead of task reference for configuration cache compatibility
            inputs.files(sourceArtifactProject.tasks.named('jar').map { it.outputs.files })
            outputs.dir(destDir)
            
            dependsOn(sourceArtifactProject.tasks.named("assemble"))
            dependsOn(sourceArtifactProject.tasks.named("build"))
        }
    }

    static def syncVersionFileToDockerStaging(Project project, String destProjectName, String destDir) {
        return project.tasks.register("syncVersionFile_${destProjectName}", Sync) {
            from(project.rootProject.layout.projectDirectory.file("VERSION"))
            into(project.layout.projectDirectory.dir(destDir))
        }
    }

    static def getDockerBuildDirName(String dockerImageName, String projectName) {
        return "docker/${dockerImageName}_${projectName}";
    }

    static def createDockerfile(Project dockerBuildProject, Project sourceArtifactProject,
                                String baseImageProjectOverride,
                                Map<String, String> dockerFilesForExternalServices,
                                String dockerImageName) {
        def projectName = sourceArtifactProject.name;
        def dockerBuildDir = "build/"+getDockerBuildDirName(dockerImageName, projectName);
        // Using tasks.register() for lazy task configuration (configuration avoidance)
        return dockerBuildProject.tasks.register("createDockerfile_${dockerImageName}", Dockerfile) {
            destFile = dockerBuildProject.file("${dockerBuildDir}/Dockerfile")
            dependsOn "copyArtifact_${dockerImageName}"
            if (baseImageProjectOverride) {
                def dependentDockerImageProjectName = dockerFilesForExternalServices.get(baseImageProjectOverride)
                if (dependentDockerImageProjectName == null) {
                    throw new GradleException("Unexpected baseImageOverride " + baseImageProjectOverride)
                }
                def dockerFileTree = dockerBuildProject.fileTree("src/main/docker/${dependentDockerImageProjectName}")
                if (!dockerFileTree.files) {
                    throw new GradleException("File tree for ${dependentDockerImageProjectName} does not exist or is empty")
                }
                def hashNonce = CommonUtils.calculateDockerHash(dockerFileTree)
                from "migrations/${baseImageProjectOverride}:${hashNonce}"
                def dependencyName = "buildDockerImage_${baseImageProjectOverride}";
                dependsOn dependencyName
                if (baseImageProjectOverride.startsWith("elasticsearch")) {
                    runCommand("sed -i -e \"s|mirrorlist=|#mirrorlist=|g\" /etc/yum.repos.d/CentOS-* ;  sed -i -e \"s|#baseurl=http://mirror.centos.org|baseurl=http://vault.centos.org|g\" /etc/yum.repos.d/CentOS-*")
                }
            } else {
                from 'amazoncorretto:17-al2023-headless'
            }

            copyFile("jars", "/jars")
            copyFile("VERSION", "VERSION")
            def jvmParams = "-XX:MaxRAMPercentage=80.0 -XX:+ExitOnOutOfMemoryError"
            // can't set the environment variable from the runtimeClasspath because the Dockerfile is
            // constructed in the configuration phase and the classpath won't be realized until the
            // execution phase.  Therefore, we need to have docker run the command to resolve the classpath
            // and it's simplest to pack that up into a helper script.
            // Application defaults are set via JAVA_TOOL_OPTIONS; users can override via JDK_JAVA_OPTIONS.
            runCommand("printf \"#!/bin/sh\\nexport JAVA_TOOL_OPTIONS=\\\"\\\${JAVA_TOOL_OPTIONS:+\\\$JAVA_TOOL_OPTIONS }${jvmParams}\\\"\\njava -cp `echo /jars/*.jar | tr \\   :` \\\"\\\$@\\\" \" > /runJavaWithClasspath.sh");
            runCommand("chmod +x /runJavaWithClasspath.sh")
            // container stay-alive
            defaultCommand('tail', '-f', '/dev/null')
            //defaultCommand('/runJavaWithClasspath.sh', '...')
        }
    }

    static def wasRequestedVersionReleasedBeforeTargetVersion(String requested, String target) {
        def requestedParts = requested.split('\\.')*.toInteger()
        def targetParts = target.split('\\.')*.toInteger()

        for (int i = 0; i < 3; i++) {
            if (requestedParts[i] < targetParts[i]) {
                return true
            } else if (requestedParts[i] > targetParts[i]) {
                return false
            }
        }
        return false // In this case, versions are equal
    }
}

class CommonConfigurations {
    static void applyCommonConfigurations(Project project) {
        project.configurations.all {
            resolutionStrategy.dependencySubstitution {
                substitute module('org.apache.xmlgraphics:batik-codec') using module('org.apache.xmlgraphics:batik-all:1.17')
            }
        }
    }
}
