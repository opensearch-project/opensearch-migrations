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
        def cacheDestination = "${registryEndpoint}/migrations_cache"
        def isEcr = registryEndpoint.contains(".ecr.") && registryEndpoint.contains(".amazonaws.com")
        if (isEcr) {
            registryDestination = "${registryEndpoint}:migrations_${imageName}_${imageTag}"
            cacheDestination = "${registryEndpoint}"
        }
        return [registryDestination, cacheDestination]
    }

    def configureJibFor = { Project project, String baseImageRegistryEndpoint, String baseImageGroup, String baseImageName,
                            String baseImageTag, String imageName, String imageTag ->
        def registryEndpoint = project.rootProject.ext.registryEndpoint.toString()
        def baseImage = getFullBaseImageIdentifier(baseImageRegistryEndpoint, baseImageGroup, baseImageName, baseImageTag)
        def registryDestination= getFullTargetImageIdentifier(registryEndpoint, imageName, imageTag)[0]
        def isECRBaseImage = baseImageRegistryEndpoint.contains(".ecr.") && baseImageRegistryEndpoint.contains(".amazonaws.com")
        def isECRTargetImage = registryEndpoint.contains(".ecr.") && registryEndpoint.contains(".amazonaws.com")
        if (isECRBaseImage || isECRTargetImage) {
            if (!project.findProperty("jib.auth.password")) {
                throw new GradleException("Missing required project property: -Pjib.auth.password. You can provide it like this: -Pjib.auth.password=\$(aws ecr get-login-password --region <e.g. us-east-2>)")
            }
        }
        project.plugins.withId('com.google.cloud.tools.jib') {
            project.jib {
                from {
                    image = baseImage
                    if (isECRBaseImage) {
                        auth {
                            username = 'AWS'
                            password = project.findProperty("jib.auth.password")
                        }
                    }
                }
                to {
                    image = registryDestination
                    if (isECRTargetImage) {
                        auth {
                            username = 'AWS'
                            password = project.findProperty("jib.auth.password")
                        }
                    }
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
                        imageTag  : "latest"
                ],
                "TrafficCapture:trafficCaptureProxyServer": [
                        baseImageRegistryEndpoint: "${rootProject.ext.registryEndpoint}",
                        baseImageGroup: "migrations",
                        baseImageName : "capture_proxy_base",
                        baseImageTag : "latest",
                        imageName : "capture_proxy",
                        imageTag  : "latest"
                ]
        ]

        projectsToConfigure.each { projPath, config ->
            rootProject.configure(rootProject.project(projPath)) {
                configureJibFor(
                        delegate,
                        config.get("baseImageRegistryEndpoint", ""),
                        config.get("baseImageGroup", ""),
                        config.baseImageName,
                        config.baseImageTag,
                        config.imageName,
                        config.imageTag
                )
            }
        }
    }

    void applyKanikoBuildTasks(Project project) {
        def registryEndpoint = project.rootProject.ext.k8sRegistryEndpoint.toString()
        registryK8sCheckTask(project)

        def consoleBaseImage = getFullBaseImageIdentifier(registryEndpoint,"migrations",
                "elasticsearch_test_console","latest")
        def kanikoImages = [
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
                                ":TrafficCapture:dockerSolution:syncArtifact_migration_console_migrationConsole",
                                "buildWithKaniko_elasticsearchTestConsole"
                        ]
                ]
        ]
        kanikoImages.each { cfg ->
            registerKanikoTask(project, cfg)
        }
    }

    void registryK8sCheckTask(Project project) {
        project.tasks.register('checkK8sEnvironment') {
            group = "kaniko"
            description = "Checks that helm, kubectl, and registry endpoint are accessible"
            def registryEndpoint = project.rootProject.ext.registryEndpoint

            doLast {
                def fail = { msg ->
                    throw new GradleException("Kaniko env check failed: ${msg}")
                }

                // Helm check
                try {
                    def helm = ["helm", "version", "--short"].execute()
                    helm.waitFor()
                    if (helm.exitValue() != 0) fail("Helm is not available: ${helm.err.text}")
                } catch (Exception e) {
                    fail("Helm check failed: ${e.message}")
                }

                // Kubectl check
                try {
                    def kubectl = ["kubectl", "version"].execute()
                    kubectl.waitFor()
                    if (kubectl.exitValue() != 0) fail("Kubectl is not configured: ${kubectl.err.text}")
                } catch (Exception e) {
                    fail("Kubectl check failed: ${e.message}")
                }

                // Registry endpoint check
                def isEcr = registryEndpoint.contains(".ecr.") && registryEndpoint.contains(".amazonaws.com")
                if (!isEcr) {
                    try {
                        def url = new URL("http://${registryEndpoint}/v2/_catalog")
                        def conn = url.openConnection()
                        conn.setConnectTimeout(2000)
                        conn.setReadTimeout(2000)
                        def text = conn.getInputStream().text
                        if (!text.contains("repositories")) {
                            fail("Registry reachable but unexpected response.")
                        }
                    } catch (Exception e) {
                        fail("Registry not reachable: ${e.message}")
                    }
                }

                println "✅ K8s environment validated for kubectl,helm, and registry endpoint"
            }
        }
    }

    void registerKanikoTask(Project project, Map cfg) {
        def uuid = UUID.randomUUID().toString().take(4)
        def serviceNameLower = cfg.serviceName.toLowerCase()
        def serviceNameForK8s = serviceNameLower + "-" + uuid
        def releaseName = "kaniko-" + serviceNameLower + "-" + uuid
        def chartPath = "deployment/k8s/charts/components/imageBuilder"
        def registryEndpoint = project.rootProject.ext.k8sRegistryEndpoint.toString()
        def (registryDestination, cacheDestination) = getFullTargetImageIdentifier(registryEndpoint, cfg.imageName.toString(), cfg.imageTag.toString())
        def optionalBootstrapPvc = project.rootProject.ext.bootstrapPvc

        def installTask = project.tasks.register("helmInstall_${cfg.serviceName}", Exec) {
            group = "kaniko"
            description = "Install Helm job for ${cfg.serviceName}"

            def helmArgs = [
                    "helm", "install", releaseName, chartPath, "--create-namespace",
                    "--set", "migrationServiceName=${serviceNameForK8s}",
                    "--set", "contextDir=${cfg.contextDir}",
                    "--set", "registryDestination=${registryDestination}",
                    "--set", "cacheDestination=${cacheDestination}",
                    "--set", "workspaceVolumePvc=${optionalBootstrapPvc}"
            ]
            def isEcr = registryEndpoint.contains(".ecr.") && registryEndpoint.contains(".amazonaws.com")
            if (isEcr) {
                helmArgs += ["--set", "serviceAccountName=migrations-sa"]
            }

            cfg.get("buildArgs", [:]).each { key, value ->
                helmArgs += ["--set", "buildArgs.${key}=${value}"]
            }
            commandLine = helmArgs
            cfg.get("requiredDependencies", []).each {
                dependsOn it
            }
            dependsOn("checkK8sEnvironment")
        }

        def waitTask = project.tasks.register("kubectlWait_${cfg.serviceName}", Exec) {
            group = "kaniko"
            description = "Wait for Kaniko job to complete for ${cfg.serviceName}"
            dependsOn installTask

            commandLine 'bash', '-c', """
            set -e
            job=image-builder-${serviceNameForK8s}
            echo "⏳ Waiting for Job: \$job..."
            for i in {1..70}; do
              status=\$(kubectl get job \$job -o jsonpath='{.status.conditions[?(@.type=="Complete")].status}' || true)
              failed=\$(kubectl get job \$job -o jsonpath='{.status.failed}' 2>/dev/null)
              failed=\${failed:-0}
              if [ "\$status" = "True" ]; then echo "Job \$job completed."; exit 0; fi
              if [ "\$failed" -ge 1 ]; then
                echo "Job \$job failed. Logs:"
                kubectl get pods -l job-name=\$job -o name | xargs -I {} kubectl logs {} --all-containers=true || true
                exit 1
              fi
              sleep 5
            done
            echo "Timed out waiting for job \$job"
            exit 1
            """
        }

        def uninstallTask = project.tasks.register("helmUninstall_${cfg.serviceName}", Exec) {
            group = "kaniko"
            description = "Uninstall Helm release for ${cfg.serviceName}"
            outputs.upToDateWhen { false }

            commandLine 'bash', '-c', """
            release="${releaseName}"
            if helm status \$release > /dev/null 2>&1; then
              echo "Uninstalling Helm release \$release"
              helm uninstall \$release
            else
              echo "Release \$release not found. Skipping."
            fi
            """
        }

        if (!project.findProperty("kaniko.helm.uninstall.disable")) {
            waitTask.configure {
                finalizedBy uninstallTask
            }
        }

        def wrapperTask = project.tasks.register("buildWithKaniko_${cfg.serviceName}") {
            group = "kaniko"
            description = "Full Kaniko build flow for ${cfg.serviceName}"
            dependsOn waitTask
        }
    }
}
