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

            stage('Build Docker Images (Minikube)') {
                steps {
                    timeout(time: 30, unit: 'MINUTES') {
                        dir('deployment/k8s') {
                            script {
                                sh "./buildDockerImagesMini.sh"
                            }
                        }
                    }
                }
            }

            stage('Install S3 CSI Driver') {
                steps {
                    timeout(time: 3, unit: 'MINUTES') {
                        script {
                            sh """
                                helm repo add aws-mountpoint-s3-csi-driver https://awslabs.github.io/mountpoint-s3-csi-driver 2>/dev/null || true
                                helm repo update aws-mountpoint-s3-csi-driver
                                kubectl create secret generic aws-secret \
                                    --namespace kube-system \
                                    --from-literal=key_id=test \
                                    --from-literal=access_key=test \
                                    --dry-run=client -o yaml | kubectl apply -f -
                                helm upgrade --install aws-mountpoint-s3-csi-driver aws-mountpoint-s3-csi-driver/aws-mountpoint-s3-csi-driver \
                                    --namespace kube-system \
                                    --set node.tolerateAllTaints=true
                            """
                        }
                    }
                }
            }

            stage('Cleanup Previous MA Deployment') {
                steps {
                    timeout(time: 3, unit: 'MINUTES') {
                        script {
                            sh "kubectl config use-context minikube"
                            sh """
                                timeout 60 kubectl delete namespace ma --ignore-not-found || {
                                    echo "Namespace delete timed out, forcing cleanup..."
                                    kubectl get all -A
                                    kubectl api-resources --verbs=list --namespaced -o name | xargs -n1 -I{} kubectl get {} -n ma -o name 2>/dev/null | xargs -r -n1 kubectl patch -n ma --type=merge -p '{"metadata":{"finalizers":null}}'
                                    kubectl delete namespace ma --ignore-not-found --force --grace-period=0 || true
                                }
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
                                sh "kubectl config use-context minikube"
                                def registryIp = sh(script: "kubectl get svc -n kube-system registry -o jsonpath='{.spec.clusterIP}'", returnStdout: true).trim()
                                sh "pipenv run app --source-version=$sourceVer --target-version=$targetVer $testIdsArg --test-reports-dir='./reports' --copy-logs --registry-prefix='${registryIp}:80/'"
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
                            sh "kubectl config use-context minikube"
                            archiveArtifacts artifacts: 'logs/**, reports/**', fingerprint: true, onlyIfSuccessful: false
                            sh "rm -rf ./reports"
                            sh "pipenv run app --delete-only"
                        }
                    }
                }
            }
        }
    }
}
