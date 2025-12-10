package org.opensearch.migrations.common

import org.gradle.api.GradleException
import org.gradle.api.tasks.Sync
import org.gradle.api.Project
import com.bmuschko.gradle.docker.tasks.image.Dockerfile

class CommonUtils {
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

    static def copyVersionFileToDockerStaging(Project project, String destProjectName, String destDir) {
        return project.tasks.register("copyVersionFile_${destProjectName}", Sync) {
            from(project.rootProject.layout.projectDirectory.file("VERSION"))
            into(project.layout.projectDirectory.dir(destDir))
            
            // Explicit inputs/outputs for incremental build support
            inputs.file(project.rootProject.layout.projectDirectory.file("VERSION"))
            outputs.dir(project.layout.projectDirectory.dir(destDir))
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
            runCommand("printf \"#!/bin/sh\\njava ${jvmParams} -cp `echo /jars/*.jar | tr \\   :` \\\"\\\$@\\\" \" > /runJavaWithClasspath.sh");
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
