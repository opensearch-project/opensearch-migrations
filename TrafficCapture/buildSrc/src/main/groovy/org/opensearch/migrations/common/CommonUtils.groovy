package org.opensearch.migrations.common

import org.gradle.api.tasks.Copy
import org.gradle.api.Project
import com.bmuschko.gradle.docker.tasks.image.Dockerfile
import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage

class CommonUtils {
    static def calculateDockerHash(def projectName, def project) {
        def MessageDigest = java.security.MessageDigest
        def digest = MessageDigest.getInstance('SHA-256')
        project.fileTree("src/main/docker/${projectName}")
                .each { file ->
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

    static def copyArtifact(Project project, String projectName) {
        def dockerBuildDir = "build/docker/${projectName}"
        def artifactsDir = "${dockerBuildDir}/jars"
        project.task("copyArtifact_${projectName}", type: Copy) {
            dependsOn ":${projectName}:build"
            dependsOn ":${projectName}:jar"
            if (projectName == "trafficCaptureProxyServerTest") {
                include "*.properties"
            }
            from { project.project(":${projectName}").configurations.findByName("runtimeClasspath").files }
            from { project.project(":${projectName}").tasks.getByName('jar') }
            into artifactsDir
            include "*.jar"
            duplicatesStrategy = 'WARN'
        }
    }

    static def createDockerfile(Project project, String projectName, Map<String, String> baseImageProjectOverrides, Map<String, String> dockerFilesForExternalServices) {
        def dockerBuildDir = "build/docker/${projectName}"
        project.task("createDockerfile_${projectName}", type: Dockerfile) {
            destFile = project.file("${dockerBuildDir}/Dockerfile")
            dependsOn "copyArtifact_${projectName}"
            def baseImageOverrideProjectName = baseImageProjectOverrides.get(projectName)
            if (baseImageOverrideProjectName) {
                def dependentDockerImageName = dockerFilesForExternalServices.get(baseImageOverrideProjectName)
                def hashNonce = CommonUtils.calculateDockerHash(baseImageOverrideProjectName, project)
                from "migrations/${dependentDockerImageName}:${hashNonce}"
                dependsOn "buildDockerImage_${baseImageOverrideProjectName}"
                runCommand("sed -i -e \"s|mirrorlist=|#mirrorlist=|g\" /etc/yum.repos.d/CentOS-* ;  sed -i -e \"s|#baseurl=http://mirror.centos.org|baseurl=http://vault.centos.org|g\" /etc/yum.repos.d/CentOS-*")
                runCommand("yum -y install nmap-ncat")
            } else {
                from 'openjdk:11-jre'
                runCommand("apt-get update && apt-get install -y netcat")
            }

            copyFile("jars", "/jars")
            // can't set the environment variable from the runtimeClasspath because the Dockerfile is
            // constructed in the configuration phase and the classpath won't be realized until the
            // execution phase.  Therefore, we need to have docker run the command to resolve the classpath
            // and it's simplest to pack that up into a helper script.
            runCommand("printf \"#!/bin/sh\\njava -cp `echo /jars/*.jar | tr \\   :` \\\"\\\$@\\\" \" > /runJavaWithClasspath.sh");
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

