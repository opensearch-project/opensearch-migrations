package org.opensearch.migrations.common

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.Exec

class RegistryImageBuildUtils {

    static def getFullBaseImageIdentifier(String baseImageRegistryEndpoint, String baseImageGroup, String baseImageName,
                                      String baseImageTag) {
        def baseImage = ""
        def isEcr = baseImageRegistryEndpoint.contains(".ecr.") && baseImageRegistryEndpoint.contains(".amazonaws.com")
        if (isEcr) {
            baseImage = "${baseImageName}_${baseImageTag}"
            if (baseImageGroup) {
                baseImage = "${baseImageGroup}_${baseImage}"
            }
            if (baseImageRegistryEndpoint) {
                baseImage = "${baseImageRegistryEndpoint}:${baseImage}"
            }
        } else {
            baseImage = "${baseImageName}:${baseImageTag}"
            if (baseImageGroup) {
                baseImage = "${baseImageGroup}/${baseImage}"
            }
            if (baseImageRegistryEndpoint) {
                baseImage = "${baseImageRegistryEndpoint}/${baseImage}"
            }
        }
        return baseImage
    }

    static def getFullTargetImageIdentifier(String registryEndpoint, String imageName, String imageTag) {
        def registryDestination = "${registryEndpoint}/migrations/${imageName}:${imageTag}"
        def cacheDestination = "${registryEndpoint}/migrations/${imageName}:cache"
        def isEcr = registryEndpoint.contains(".ecr.") && registryEndpoint.contains(".amazonaws.com")
        if (isEcr) {
            registryDestination = "${registryEndpoint}:migrations_${imageName}_${imageTag}"
            cacheDestination = "${registryEndpoint}:migrations_${imageName}_cache"
        }
        return [registryDestination, cacheDestination]
    }

    def configureJibFor = { Project project, String baseImageRegistryEndpoint, String baseImageGroup, String baseImageName,
                            String baseImageTag, String imageName, String imageTag, List<String> requiredDependencies ->
        def registryEndpoint = project.rootProject.ext.registryEndpoint.toString()
        def baseImage = getFullBaseImageIdentifier(baseImageRegistryEndpoint, baseImageGroup, baseImageName, baseImageTag)
        def registryDestination= getFullTargetImageIdentifier(registryEndpoint, imageName, imageTag)[0]
        requiredDependencies.each { taskPath ->
            project.tasks.named("jib").configure {
                dependsOn project.rootProject.tasks.named(taskPath)
            }
        }
        project.plugins.withId('com.google.cloud.tools.jib') {
            project.jib {
                from {
                    image = baseImage
                    platforms {
                        platform {
                            architecture = project.rootProject.ext.imageArch
                            os = 'linux'
                        }
                    }
                }
                to {
                    image = registryDestination
                }
                extraDirectories {
                    paths {
                        path {
                            from = project.file("docker")
                            into = '/'
                        }
                    }
                    permissions = [
                            '/runJavaWithClasspath.sh': '755'
                    ]
                }
                allowInsecureRegistries = true
                container {
                    entrypoint = ['tail', '-f', '/dev/null']
                }
            }
        }
    }

    void applyJibConfigurations(Project rootProject) {
        def projectsToConfigure = [
                "TrafficCapture:trafficReplayer": [
                        baseImageName : "amazoncorretto",
                        baseImageTag : "17-al2023-headless",
                        imageName : "traffic_replayer",
                        imageTag  : "latest",
                ],
                "TrafficCapture:trafficCaptureProxyServer": [
                        baseImageRegistryEndpoint: "${rootProject.ext.registryEndpoint}",
                        baseImageGroup: "migrations",
                        baseImageName : "capture_proxy_base",
                        baseImageTag : "latest",
                        imageName : "capture_proxy",
                        imageTag  : "latest",
                        requiredDependencies:  ["buildKit_captureProxyBase"]
                ]
        ]

        projectsToConfigure.each { projPath, config ->
            rootProject.configure(rootProject.project(projPath)) {
                configureJibFor(
                        it,
                        config.get("baseImageRegistryEndpoint", "").toString(),
                        config.get("baseImageGroup", "").toString(),
                        config.baseImageName.toString(),
                        config.baseImageTag.toString(),
                        config.imageName.toString(),
                        config.imageTag.toString(),
                        (List<String>) config.get("requiredDependencies", [])
                )
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

            project.tasks.matching { it.name.startsWith("buildKit") || it.name.endsWith("jib") }.configureEach {
                dependsOn project.tasks.named("loginToECR")
            }
        }
    }

    void registerBuildKitTask(Project project, Map cfg) {
        def registryEndpoint = project.ext.buildKitRegistryEndpoint.toString()
        def builder = project.findProperty("builder") ?: "local-remote-builder"
        def imageName = cfg.get("imageName").toString()
        def imageTag = cfg.get("imageTag", "latest").toString()
        def contextDir = cfg.get("contextDir", ".")
        def serviceName = cfg.get("serviceName")
        def contextPath = project.file(contextDir).path
        def (registryDestination, cacheDestination) = getFullTargetImageIdentifier(registryEndpoint, imageName, imageTag)

        def buildTaskName = "buildKit_${serviceName}"
        project.tasks.register(buildTaskName, Exec) {
            group = "docker"
            description = "Build and push ${registryDestination} with caching"

            cfg.get("requiredDependencies", []).each {
                dependsOn it
            }

            def buildArgFlags = cfg.get("buildArgs", [:]).collect { key, value ->
                "--build-arg ${key}=${value}"
            }

            def fullArgs = [
                    "docker buildx build",
                    "--platform linux/${project.ext.imageArch}",
                    "--builder ${builder}",
                    "-t ${registryDestination}",
                    "--push",
                    "--cache-to=type=registry,ref=${cacheDestination},mode=max",
                    "--cache-from=type=registry,ref=${cacheDestination}"
            ]
            buildArgFlags.each { fullArgs.add(it) }
            fullArgs.add(contextPath)
            def buildCommand = fullArgs.join(" ")

            doFirst {
                println "Executing buildx command: ${buildCommand}"
            }

            commandLine 'sh', '-c', buildCommand
        }
    }

    void applyBuildKitConfigurations(Project rootProject) {
        def registryEndpoint = rootProject.ext.buildKitRegistryEndpoint.toString()
        def consoleBaseImage = getFullBaseImageIdentifier(registryEndpoint,"migrations",
                "elasticsearch_test_console","latest")
        def projects = [
                [
                        serviceName: "elasticsearchTestConsole",
                        contextDir: "TrafficCapture/dockerSolution/src/main/docker/elasticsearchTestConsole",
                        imageName:  "elasticsearch_test_console",
                        imageTag:   "latest"
                ],
                [
                        serviceName: "captureProxyBase",
                        contextDir: "TrafficCapture/dockerSolution/src/main/docker/captureProxyBase",
                        imageName:  "capture_proxy_base",
                        imageTag:   "latest"
                ],
                [
                        serviceName: "reindexFromSnapshot",
                        contextDir: "DocumentsFromSnapshotMigration/docker",
                        imageName:  "reindex_from_snapshot",
                        imageTag:   "latest",
                        requiredDependencies: [
                                ":DocumentsFromSnapshotMigration:copyDockerRuntimeJars"
                        ]
                ],
                [
                        serviceName: "migrationConsole",
                        contextDir: "TrafficCapture/dockerSolution/build/docker/migration_console_migrationConsole",
                        imageName:  "migration_console",
                        imageTag:   "latest",
                        buildArgs: [
                                BASE_IMAGE: "${consoleBaseImage}"
                        ],
                        requiredDependencies: [
                                ":TrafficCapture:dockerSolution:syncArtifact_migration_console_migrationConsole_noDockerBuild",
                                "buildKit_elasticsearchTestConsole"
                        ]
                ]
        ]
        projects.each { cfg ->
            registerBuildKitTask(rootProject, cfg)
        }
    }
}
