def call(Map config = [:]) {
    def jobName = config.jobName ?: "k8s-local-integ-test"
    def sourceVersion = config.sourceVersion ?: ""
    def targetVersion = config.targetVersion ?: ""
    def testIds = config.testIds ?: ""
    def traceTestIds = config.traceTestIds ?: ""
    def traceValuesFile = config.traceValuesFile ?: "../../deployment/k8s/charts/aggregates/migrationAssistantWithArgo/valuesTraceJaeger.yaml"
    def traceBackend = config.traceBackend ?: "jaeger"

    def versions = migrationVersions()
    def allSourceVersions = versions.sourceVersions
    def allTargetVersions = versions.targetVersions

    pipeline {
        agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }

        environment {
            KIND_VERSION = 'v0.31.0'
            KIND_NODE_IMAGE = 'kindest/node:v1.35.1@sha256:05d7bcdefbda08b4e038f644c4df690cdac3fba8b06f8289f30e10026720a1ab'
        }

        parameters {
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/opensearch-project/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Git branch to use for repository')
            string(name: 'GIT_COMMIT', defaultValue: '', description: '(Optional) Specific commit to checkout after cloning branch')
            choice(
                    name: 'SOURCE_VERSION',
                    choices: ['all'] + allSourceVersions,
                    description: 'Pick a specific source version, or "all"'
            )
            choice(
                    name: 'TARGET_VERSION',
                    choices: ['all'] + allTargetVersions,
                    description: 'Pick a specific target version, or "all"'
            )
            string(name: 'TEST_IDS', defaultValue: 'all', description: 'Test IDs to execute. Use comma separated list e.g. "0001,0004" or "all" for all tests')
        }

        options {
            timeout(time: 6, unit: 'HOURS')
            buildDiscarder(logRotator(daysToKeepStr: '30'))
            skipDefaultCheckout(true)
        }

        triggers {
            GenericTrigger(
                    genericVariables: [
                            [key: 'GIT_REPO_URL', value: '$.GIT_REPO_URL'],
                            [key: 'GIT_BRANCH', value: '$.GIT_BRANCH'],
                            [key: 'GIT_COMMIT', value: '$.GIT_COMMIT'],
                            [key: 'job_name', value: '$.job_name']
                    ],
                    tokenCredentialId: 'jenkins-migrations-generic-webhook-token',
                    causeString: 'Triggered by PR on opensearch-migrations repository',
                    regexpFilterExpression: "^${jobName}\$",
                    regexpFilterText: '$job_name'
            )
            cron(periodicCron(jobName))
        }

        stages {
            stage('Checkout') {
                steps {
                    checkoutStep(branch: params.GIT_BRANCH, repo: params.GIT_REPO_URL, commit: params.GIT_COMMIT)
                }
            }

            stage('Configure Kind Host') {
                steps {
                    // Kubernetes 1.35 requires cgroup v2. Also ensure Fluent Bit can create
                    // its tail watcher after the control plane starts.
                    sh './jenkins/configureKindHost.sh'
                }
            }

            stage('Install Kind') {
                steps {
                    // Install into this workspace from a versioned, checksum-verified cache
                    // under the agent's home directory.
                    sh 'KIND_INSTALL_DIR="${WORKSPACE}/.ci-bin" ./jenkins/installKind.sh'
                }
            }

            stage('Recreate Kind') {
                steps {
                    // Always recreate. The shared docker-hosted registry/buildkit containers
                    // and their registry-data + buildkit-cache volumes live on host Docker,
                    // so they survive `kind delete cluster` and image layers are reused.
                    //
                    // The kind nodes run as Docker containers. The local registry is configured
                    // as an insecure HTTP registry in the kind node containerd configuration.
                    timeout(time: 15, unit: 'MINUTES') {
                        // Tear down the registry before deleting the kind cluster.
                        // This is only necessary if the registry container is attached to
                        // the kind network and would otherwise prevent network cleanup.
                        sh '. ./buildImages/backends/dockerHostedBuildkit.sh && teardown_registry_container'

                        // Keep the single-node topology previously provided by Minikube. These
                        // tests do not need separate worker nodes.
                        // Recreate the ephemeral cluster once for transient startup failures.
                        retry(2) {
                            sh '"${WORKSPACE}/.ci-bin/kind" delete cluster --name ma || true'

                            // Kubernetes 1.35 enables ImageVolume by default. The pinned manifest
                            // digest keeps the node image reproducible across architectures.
                            sh '"${WORKSPACE}/.ci-bin/kind" create cluster --name ma --config ./deployment/k8s/kindClusterConfigSingleNode.yaml --image "${KIND_NODE_IMAGE}"'

                            // kind nodes have their own containerd. Configure its registry hosts
                            // with a token obtained from the Jenkins agent's instance role.
                            sh '''
                                ECR_PULL_THROUGH_ENDPOINT="$(bash -l -c 'printf %s "$ECR_PULL_THROUGH_ENDPOINT"')" \
                                    KIND_BIN="${WORKSPACE}/.ci-bin/kind" \
                                    KIND_CLUSTER_NAME=ma \
                                    ./jenkins/configureKindCluster.sh
                            '''
                        }
                    }
                }
            }

            stage('Build Docker Images (BuildKit)') {
                steps {
                    timeout(time: 30, unit: 'MINUTES') {
                        script {
                            sh "kubectl config unset current-context || true"
                            // Bring up the docker-hosted registry/buildkit, then configure each
                            // kind node to reach the registry through the host's published port.
                            sh '''
                                set -eu
                                . ./buildImages/backends/dockerHostedBuildkit.sh

                                KUBE_CONTEXT=kind-ma setup_build_backend
                                kind_nodes=()
                                while IFS= read -r node; do
                                    [ -n "$node" ] && kind_nodes+=("$node")
                                done < <("${WORKSPACE}/.ci-bin/kind" get nodes --name ma)
                                connect_cluster_to_registry_network kind "${kind_nodes[@]}"
                            '''
                            def pullThroughCacheEndpoint = sh(script: 'bash -l -c \'echo -n $ECR_PULL_THROUGH_ENDPOINT\'', returnStdout: true).trim()
                            // The load-test images are their own aggregate (see buildImages/build.gradle),
                            // so buildImagesToRegistry_* does not build them. Only the 008x cases need
                            // them, and those need explicit selection, so build them only on demand.
                            // "008" must stay equal to LOAD_TEST_ID_PREFIX in test_runner.py.
                            def selectedTestIds = testIds ?: params.TEST_IDS
                            def loadTestTarget = selectedTestIds.split(',').any { it.trim().startsWith("008") }
                                    ? " :buildImages:buildKitLoadTestAll_amd64" : ""
                            sh "./gradlew :buildImages:buildImagesToRegistry_amd64 :buildImages:buildKitTestAll_amd64${loadTestTarget} -Pbuilder=builder-kind-ma -PregistryEndpoint=localhost:5001 -x test --info --stacktrace --profile --scan${pullThroughCacheEndpoint ? " -PpullThroughCacheEndpoint=${pullThroughCacheEndpoint}" : ""}"
                            // Keep builder-kind-ma alive across runs so the buildkit cache persists.
                        }
                    }
                }
            }

            stage('Perform Python E2E Tests') {
                steps {
                    timeout(time: 5, unit: 'HOURS') {
                        dir('libraries/testAutomation') {
                            script {
                                def requestedSourceVersion = params.SOURCE_VERSION ?: ""
                                def requestedTargetVersion = params.TARGET_VERSION ?: ""
                                def sourceVer = requestedSourceVersion && requestedSourceVersion != 'all'
                                        ? requestedSourceVersion
                                        : sourceVersion ?: requestedSourceVersion
                                def targetVer = requestedTargetVersion && requestedTargetVersion != 'all'
                                        ? requestedTargetVersion
                                        : targetVersion ?: requestedTargetVersion
                                currentBuild.description = "${sourceVer} → ${targetVer}"
                                // --source-version accepts one or more space-separated values; convert commas for multi-source jobs
                                def sourceVerArg = sourceVer ? sourceVer.replace(',', ' ') : 'all'
                                def testIdsArg = ""
                                def testIdsResolved = testIds ?: params.TEST_IDS
                                if (testIdsResolved != "" && testIdsResolved != "all") {
                                    testIdsArg = "--test-ids='$testIdsResolved'"
                                }
                                def traceArgs = ""
                                if (traceTestIds != "") {
                                    traceArgs = "--trace-test-ids='$traceTestIds' --trace-values-file='$traceValuesFile' --trace-backend='$traceBackend'"
                                }
                                sh "pipenv install --deploy"
                                sh "mkdir -p ./reports"
                                sh "kubectl config unset current-context || true"
                                sh "pipenv run app --source-version $sourceVerArg --target-version=$targetVer $testIdsArg $traceArgs --test-reports-dir='./reports' --copy-logs --registry-prefix='docker-registry:5001/' --kube-context=kind-ma --capture-proxy-service-type=ClusterIP"
                            }
                        }
                    }
                }
            }
        }
        post {
            always {
                timeout(time: 15, unit: 'MINUTES') {
                    dir('libraries/testAutomation') {
                        script {
                            sh "pipenv install --deploy"
                            sh "kubectl config unset current-context || true"
                            archiveArtifacts artifacts: 'logs/**, reports/**', fingerprint: true, onlyIfSuccessful: false
                            sh "rm -rf ./reports"
                            sh '''
                                if kubectl config get-contexts -o name | grep -qx kind-ma; then
                                    pipenv run app --delete-only --kube-context=kind-ma
                                else
                                    echo "Skipping cleanup because Kubernetes context kind-ma does not exist"
                                fi
                            '''
                        }
                    }
                }
            }
        }
    }
}
