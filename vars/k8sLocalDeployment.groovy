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

            stage('Recreate Minikube') {
                steps {
                    // Always recreate. The shared docker-hosted registry/buildkit containers
                    // and their registry-data + buildkit-cache volumes live on host Docker,
                    // so they survive `minikube delete` and image layers are reused.
                    // Use minikube's default cri-dockerd runtime + bridge CNI: forcing
                    // containerd makes minikube install kindnet, which pulls a docker.io
                    // image at every cluster start and breaks under Docker Hub rate limits
                    // in CI. `--insecure-registry=docker-registry:5001` lets cri-dockerd
                    // accept plain HTTP pulls from the host-bound registry port.
                    timeout(time: 10, unit: 'MINUTES') {
                        // Tear down the registry before minikube/network so that
                        // docker network rm isn't blocked by a connected container.
                        sh '. ./buildImages/backends/dockerHostedBuildkit.sh && teardown_registry_container'
                        sh 'minikube delete || true'
                        // minikube delete sometimes leaves the "minikube" docker network behind
                        // with its 192.168.49.0/24 subnet; drop it so the next start can recreate
                        // it without "address already in use".
                        sh 'docker network rm minikube 2>/dev/null || true'
                        sh 'minikube start --driver=docker --insecure-registry=docker-registry:5001'
                    }
                }
            }

            stage('Build Docker Images (BuildKit)') {
                steps {
                    timeout(time: 30, unit: 'MINUTES') {
                        script {
                            sh "kubectl config unset current-context || true"
                            // Bring up the docker-hosted registry/buildkit, then configure each
                            // minikube node to resolve docker-registry to the host gateway and
                            // pull from the host-bound port (no docker network connect needed).
                            sh '''
                                set -eu
                                . ./buildImages/backends/dockerHostedBuildkit.sh
                                KUBE_CONTEXT=minikube setup_build_backend
                                mk_nodes=()
                                while IFS= read -r node; do
                                    [ -n "$node" ] && mk_nodes+=("$node")
                                done < <(minikube node list 2>/dev/null | awk '{print $1}')
                                connect_cluster_to_registry_network minikube "${mk_nodes[@]}"
                            '''
                            def pullThroughCacheEndpoint = sh(script: 'bash -l -c \'echo -n $ECR_PULL_THROUGH_ENDPOINT\'', returnStdout: true).trim()
                            sh "./gradlew :buildImages:buildImagesToRegistry_amd64 :buildImages:buildKitTestAll_amd64 -PincludeSolr773TestImage -Pbuilder=builder-minikube -PregistryEndpoint=localhost:5001 -x test --info --stacktrace --profile --scan${pullThroughCacheEndpoint ? " -PpullThroughCacheEndpoint=${pullThroughCacheEndpoint}" : ""}"
                            // Keep builder-minikube alive across runs so the buildkit cache persists.
                        }
                    }
                }
            }

            stage('Perform Python E2E Tests') {
                steps {
                    timeout(time: 5, unit: 'HOURS') {
                        dir('libraries/testAutomation') {
                            script {
                                def sourceVer = sourceVersion ?: params.SOURCE_VERSION
                                def targetVer = targetVersion ?: params.TARGET_VERSION
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
                                sh "pipenv run app --source-version $sourceVerArg --target-version=$targetVer $testIdsArg $traceArgs --test-reports-dir='./reports' --copy-logs --registry-prefix='docker-registry:5001/' --kube-context=minikube --capture-proxy-service-type=ClusterIP"
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
                            sh "pipenv run app --delete-only --kube-context=minikube"
                        }
                    }
                }
            }
        }
    }
}
