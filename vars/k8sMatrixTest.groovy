def call(Map config = [:]) {
    pipeline {
        agent none
        parameters {
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/opensearch-project/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Git branch to use for repository')
            choice(
                    name: 'SOURCE_VERSION',
                    choices: ['(all)', 'ES_5.6'],
                    description: 'Pick a specific source version, or "(all)"'
            )
            choice(
                    name: 'TARGET_VERSION',
                    choices: ['(all)', 'OS_2.19'],
                    description: 'Pick a specific target version, or "(all)"'
            )
        }

        options {
            timeout(time: 1, unit: 'HOURS')
            buildDiscarder(logRotator(daysToKeepStr: '30'))
            skipDefaultCheckout(true)
        }

        triggers {
            cron('@hourly')
        }
        matrix {
            axes {
                axis {
                    name 'SOURCE_VERSION_AXIS'
                    values 'ES_5.6', 'ES_6.8', 'ES_7.10'
                }
                axis {
                    name 'TARGET_VERSION_AXIS'
                    values 'OS_2.19', 'OS_2.20', 'OS_2.21'
                }
            }

            agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }


            when {
                expression {
                    // When triggered by the cron job, no parameters are set,
                    // so treat it like "(all)" and run the full cross-product
                    (params.SOURCE_VERSION == '(all)' || params.SOURCE_VERSION == SOURCE_VERSION_AXIS) &&
                            (params.TARGET_VERSION == '(all)' || params.TARGET_VERSION == TARGET_VERSION_AXIS)
                }
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
                            echo "Building migration from ${SOURCE_VERSION_AXIS} to ${TARGET_VERSION_AXIS}"
                        }
                    }
                }

//                stage('Check Minikube Status') {
//                    steps {
//                        timeout(time: 5, unit: 'MINUTES') {
//                            script {
//                                def status = sh(script: "minikube status --format='{{.Host}}'", returnStdout: true).trim()
//                                if (status == "Running") {
//                                    echo "✅ Minikube is running"
//                                } else {
//                                    echo "Minikube is not running, status: " + status
//                                    sh(script: "minikube delete", returnStdout: true)
//                                    sh(script: "minikube start", returnStdout: true)
//                                    def status2 = sh(script: "minikube status --format='{{.Host}}'", returnStdout: true).trim()
//                                    if (status2 == "Running") {
//                                        echo "✅ Minikube was started as is running"
//                                    } else {
//                                        error("❌ Minikube failed to start")
//                                    }
//                                }
//                            }
//                        }
//                    }
//                }
//
//                stage('Build Docker Images (Minikube)') {
//                    steps {
//                        timeout(time: 30, unit: 'MINUTES') {
//                            dir('deployment/k8s') {
//                                script {
//                                    sh "./buildDockerImagesMini.sh"
//                                }
//                            }
//                        }
//                    }
//                }
//
//                stage('Perform Python E2E Tests') {
//                    steps {
//                        timeout(time: 15, unit: 'MINUTES') {
//                            dir('libraries/testAutomation') {
//                                script {
//                                    sh "pipenv install --deploy"
//                                    sh "pipenv run app --source-version=$sourceVersion --target-version=$targetVersion $testIdsArg --skip-delete"
//                                }
//                            }
//                        }
//                    }
//                }
            }
//            post {
//                always {
//                    timeout(time: 15, unit: 'MINUTES') {
//                        dir('libraries/testAutomation') {
//                            script {
//                                sh "pipenv install --deploy"
//                                sh "pipenv run app --copy-logs-only"
//                                archiveArtifacts artifacts: 'logs/**', fingerprint: true, onlyIfSuccessful: false
//                                sh "pipenv run app --delete-only"
//                            }
//                        }
//                    }
//                }
//            }
        }
    }
}


//
//
//
//
//
//
//
//pipeline {
//    agent none
//
//    triggers {
//        // Run hourly at the top of the hour
//        cron('@hourly')
//    }
//
//    parameters {
//        choice(
//                name: 'SOURCE_VERSION',
//                choices: ['(all)', 'ES_5.6', 'ES_6.8', 'ES_7.10'],
//                description: 'Pick a specific source version, or "(all)" for all'
//        )
//        choice(
//                name: 'TARGET_VERSION',
//                choices: ['(all)', 'OS_2.19', 'OS_2.20', 'OS_2.21'],
//                description: 'Pick a specific target version, or "(all)" for all'
//        )
//    }
//
//    matrix {
//        axes {
//            axis {
//                name 'SOURCE_VERSION_AXIS'
//                values 'ES_5.6', 'ES_6.8', 'ES_7.10'
//            }
//            axis {
//                name 'TARGET_VERSION_AXIS'
//                values 'OS_2.19', 'OS_2.20', 'OS_2.21'
//            }
//        }
//
//        agent { label 'linux' }
//
//        when {
//            expression {
//                // When triggered by the cron job, no parameters are set,
//                // so treat it like "(all)" and run the full cross-product
//                (params.SOURCE_VERSION == '(all)' || params.SOURCE_VERSION == SOURCE_VERSION_AXIS) &&
//                        (params.TARGET_VERSION == '(all)' || params.TARGET_VERSION == TARGET_VERSION_AXIS)
//            }
//        }
//
//        stages {
//            stage('Build') {
//                steps {
//                    echo "Building migration from ${SOURCE_VERSION_AXIS} to ${TARGET_VERSION_AXIS}"
//                }
//            }
//            stage('Test') {
//                steps {
//                    echo "Testing migration from ${SOURCE_VERSION_AXIS} to ${TARGET_VERSION_AXIS}"
//                }
//            }
//        }
//    }
//}
