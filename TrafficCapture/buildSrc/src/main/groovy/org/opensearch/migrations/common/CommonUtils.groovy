package org.opensearch.migrations.common

import org.gradle.api.tasks.Copy
import org.gradle.api.Project

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

    static def copyArtifact(String projectName, Project project) {
        def dockerBuildDir = "build/docker/${projectName}"
        def artifactsDir = "${dockerBuildDir}/jars"
        project.task("copyArtifact_${projectName}", type: Copy) {
            dependsOn ":${projectName}:build"
            dependsOn ":${projectName}:jar"
            from { project.project(":${projectName}").configurations.findByName("runtimeClasspath").files }
            from { project.project(":${projectName}").tasks.getByName('jar') }
            into artifactsDir
            include "*.jar"
            duplicatesStrategy = 'WARN'
            if (projectName == "trafficCaptureProxyServerTest") {
                include "*.properties"
                from project.file("src/main/docker/${projectName}/jmeter.properties").absolutePath
                into "${dockerBuildDir}"
            }
        }
    }
}

class CommonConfigurations {
    static void applyCommonConfigurations(Project project) {
        project.configurations.all {
            resolutionStrategy.dependencySubstitution {
                substitute module('org.apache.xmlgraphics:batik-codec') using module('org.apache.xmlgraphics:batik-all:1.15')
            }
        }
    }
}

