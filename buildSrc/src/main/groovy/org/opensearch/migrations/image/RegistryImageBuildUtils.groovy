package org.opensearch.migrations.image

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.opensearch.migrations.common.CommonUtils

class RegistryImageBuildUtils {

    def configureJibFor = { Project project, String baseImageRegistryEndpoint, String baseImageGroup, String baseImageName,
                            String baseImageTag, String imageName, String imageTag, Map<String, List<String>> requiredDependencies ->
        def registryEndpoint = project.rootProject.ext.registryEndpoint.toString()
        def baseFormatter = ImageRegistryFormatterFactory.getFormatter(baseImageRegistryEndpoint)
        def targetFormatter = ImageRegistryFormatterFactory.getFormatter(registryEndpoint)
        def baseImage = baseFormatter.getFullBaseImageIdentifier(baseImageRegistryEndpoint, baseImageGroup, baseImageName, baseImageTag)
        def registryDestination= targetFormatter.getFullTargetImageIdentifier(registryEndpoint, imageName, imageTag)[0]
        CommonUtils.copyVersionFileToDockerStaging(project, imageName, "build/versionDir")
        requiredDependencies.each { projectPath, taskNames ->
            def targetProject = projectPath ? project.rootProject.project(projectPath) : project.rootProject
            taskNames.each { taskName ->
                project.tasks.named("jib").configure {
                    dependsOn(targetProject.tasks.named(taskName))
                }
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
                        path {
                            from = project.file("build/versionDir")
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

    void applyJibConfigurations(Project rootProject, Map<String, Map> projectsToConfigure) {
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
                        (Map<String, List<String>>) config.get("requiredDependencies", [])
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

    void registerBuildKitTask(Project project, Map cfg) {
        def registryEndpoint = project.ext.buildKitRegistryEndpoint.toString()
        def builder = project.findProperty("builder") ?: "local-remote-builder"
        def imageName = cfg.get("imageName").toString()
        def imageTag = cfg.get("imageTag", "latest").toString()
        def contextDir = cfg.get("contextDir", ".")
        def serviceName = cfg.get("serviceName")
        def contextPath = project.file(contextDir).path
        def formatter = ImageRegistryFormatterFactory.getFormatter(registryEndpoint)
        def (registryDestination, cacheDestination) = formatter.getFullTargetImageIdentifier(registryEndpoint, imageName, imageTag)

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

    void applyBuildKitConfigurations(Project rootProject, List<Map> projects) {
        projects.each { cfg ->
            registerBuildKitTask(rootProject, cfg)
        }
    }
}
