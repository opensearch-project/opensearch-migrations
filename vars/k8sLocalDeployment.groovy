def call(Map config = [:]) {
    def jobName = config.jobName ?: "k8s-local-integ-test"
    def sourceVersion = config.sourceVersion ?: ""
    def targetVersion = config.targetVersion ?: ""
    def testIds = config.testIds ?: ""

    def allSourceVersions = ['ES_1.5', 'ES_2.4', 'ES_5.6', 'ES_6.8', 'ES_7.10']
    def allTargetVersions = ['OS_1.3', 'OS_2.19', 'OS_3.1']

    pipeline {
        agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }

        parameters {
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/opensearch-project/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Git branch to use for repository')
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
            timeout(time: 3, unit: 'HOURS')
            buildDiscarder(logRotator(daysToKeepStr: '30'))
            skipDefaultCheckout(true)
        }

        triggers {
            GenericTrigger(
                    genericVariables: [
                            [key: 'GIT_REPO_URL', value: '$.GIT_REPO_URL'],
                            [key: 'GIT_BRANCH', value: '$.GIT_BRANCH'],
                            [key: 'job_name', value: '$.job_name']
                    ],
                    tokenCredentialId: 'jenkins-migrations-generic-webhook-token',
                    causeString: 'Triggered by PR on opensearch-migrations repository',
                    regexpFilterExpression: "^$jobName\$",
                    regexpFilterText: "\$job_name",
            )
        }

        stages {
            stage('Checkout') {
                steps {
                    script {
                        sh 'sudo chown -R $(whoami) .'
                        sh 'sudo chmod -R u+w .'
                        // If in an existing git repository, remove any additional files in git tree that are not listed in .gitignore
                        if (sh(script: 'git rev-parse --git-dir > /dev/null 2>&1', returnStatus: true) == 0) {
                            echo 'Cleaning any existing git files in workspace'
                            sh 'git reset --hard'
                            sh 'git clean -fd'
                        } else {
                            echo 'No git project detected, this is likely an initial run of this pipeline on the worker'
                        }
                        git branch: "${params.GIT_BRANCH}", url: "${params.GIT_REPO_URL}"
                    }
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

            stage('Build Docker Images (BuildKit)') {
                steps {
                    timeout(time: 30, unit: 'MINUTES') {
                        script {
                            sh "helm --kube-context=minikube uninstall buildkit -n buildkit 2>/dev/null || true"
                            sh "USE_LOCAL_REGISTRY=true KUBE_CONTEXT=minikube BUILDKIT_HELM_ARGS='--set buildkitd.maxParallelism=16 --set buildkitd.resources.requests.cpu=0 --set buildkitd.resources.requests.memory=0 --set buildkitd.resources.limits.cpu=0 --set buildkitd.resources.limits.memory=0' ./buildImages/setUpK8sImageBuildServices.sh"
                            sh "./gradlew :buildImages:buildImagesToRegistry_amd64 -x test --info --stacktrace --profile --scan"
                            sh "docker buildx rm local-remote-builder 2>/dev/null || true"
                            sh "helm --kube-context=minikube uninstall buildkit -n buildkit 2>/dev/null || true"
                        }
                    }
                }
            }

            stage('Cleanup Previous MA Deployment') {
                steps {
                    timeout(time: 3, unit: 'MINUTES') {
                        script {
                            sh """
                                # Attempt clean helm uninstall first (triggers hook-based cleanup)
                                helm --kube-context=minikube uninstall ma -n ma || true

                                # Delete kyverno webhooks in case helm uninstall didn't run cleanly
                                kubectl --context=minikube delete mutatingwebhookconfigurations -l app.kubernetes.io/instance=kyverno --ignore-not-found || true
                                kubectl --context=minikube delete validatingwebhookconfigurations -l app.kubernetes.io/instance=kyverno --ignore-not-found || true

                                # Force-delete namespaces if they still exist
                                kubectl --context=minikube delete namespace kyverno-ma --ignore-not-found --grace-period=0 || true
                                kubectl --context=minikube delete namespace ma --ignore-not-found --grace-period=0 --force || true
                            """
                        }
                    }
                }
            }

            stage('Perform Python E2E Tests') {
                steps {
                    timeout(time: 2, unit: 'HOURS') {
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
                                def registryIp = sh(script: "kubectl --context=minikube get svc -n buildkit docker-registry -o jsonpath='{.spec.clusterIP}'", returnStdout: true).trim()
                                sh "pipenv run app --source-version=$sourceVer --target-version=$targetVer $testIdsArg --test-reports-dir='./reports' --copy-logs --registry-prefix='${registryIp}:5000/' --kube-context=minikube"
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
