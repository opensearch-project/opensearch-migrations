def call(Map config = [:]) {
    def jobName = config.jobName ?: "k8s-local-integ-test"
    def sourceVersion = config.sourceVersion ?: ""
    def targetVersion = config.targetVersion ?: ""
    def testIds = config.testIds ?: ""

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

            stage('Check Minikube Status') {
                steps {
                    timeout(time: 5, unit: 'MINUTES') {
                        script {
                            def status = sh(script: "minikube status --format='{{.Host}}'", returnStdout: true).trim()
                            if (status == "Running") {
                                echo "✅ Minikube is running"
                            } else {
                                echo "Minikube is not running, status: " + status
                                sh(script: "minikube delete", returnStdout: true)
                                sh(script: "minikube start", returnStdout: true)
                                def status2 = sh(script: "minikube status --format='{{.Host}}'", returnStdout: true).trim()
                                if (status2 == "Running") {
                                    echo "✅ Minikube was started as is running"
                                } else {
                                    error("❌ Minikube failed to start")
                                }
                            }
                        }
                    }
                }
            }

            stage('Cleanup Previous MA Deployment') {
                steps {
                    timeout(time: 3, unit: 'MINUTES') {
                        script {
                            sh "kubectl config unset current-context || true"
                            sh """
                                # Delete all webhook configurations first — stale webhooks can block all API calls
                                kubectl --context=minikube delete mutatingwebhookconfigurations --all --ignore-not-found || true
                                kubectl --context=minikube delete validatingwebhookconfigurations --all --ignore-not-found || true

                                # Helm uninstall with --no-hooks to avoid failing pre-delete hooks on terminating namespaces
                                helm --kube-context=minikube uninstall ma -n ma --no-hooks || true

                                # Force-delete namespaces if they still exist
                                kubectl --context=minikube delete namespace kyverno-ma --ignore-not-found --grace-period=0 || true
                                kubectl --context=minikube delete namespace ma --ignore-not-found --grace-period=0 --force || true
                            """
                        }
                    }
                }
            }

            stage('Build Docker Images (BuildKit)') {
                steps {
                    timeout(time: 30, unit: 'MINUTES') {
                        script {
                            sh "kubectl config unset current-context || true"
                            // Bring up the docker-hosted registry/buildkit, then attach the minikube node
                            // container to the same docker network so containerd can pull localhost:5001.
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
                            sh "./gradlew :buildImages:buildImagesToRegistry_amd64 :buildImages:buildKitTestAll_amd64 -Pbuilder=builder-minikube -PregistryEndpoint=localhost:5001 -x test --info --stacktrace --profile --scan${pullThroughCacheEndpoint ? " -PpullThroughCacheEndpoint=${pullThroughCacheEndpoint}" : ""}"
                            sh "docker buildx rm builder-minikube 2>/dev/null || true"
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
                                def testIdsArg = ""
                                def testIdsResolved = testIds ?: params.TEST_IDS
                                if (testIdsResolved != "" && testIdsResolved != "all") {
                                    testIdsArg = "--test-ids='$testIdsResolved'"
                                }
                                sh "pipenv install --deploy"
                                sh "mkdir -p ./reports"
                                sh "kubectl config unset current-context || true"
                                sh "pipenv run app --source-version=$sourceVer --target-version=$targetVer $testIdsArg --test-reports-dir='./reports' --copy-logs --registry-prefix='localhost:5001/' --kube-context=minikube"
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
