def call(Map config = [:]) {
    pipeline {
        agent none  // MUST be 'none' for matrix pipelines

        parameters {
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/lewijacn/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: 'k8s-matrix-test', description: 'Git branch to use for repository')
            choice(
                    name: 'SOURCE_VERSION',
                    choices: ['(all)', 'ES_5.6', 'ES_7.10'],
                    description: 'Pick a specific source version, or "(all)"'
            )
            choice(
                    name: 'TARGET_VERSION',
                    choices: ['(all)', 'OS_2.19', 'OS_2.20'],
                    description: 'Pick a specific target version, or "(all)"'
            )
        }

        options {
            timeout(time: 2, unit: 'HOURS')
            buildDiscarder(logRotator(daysToKeepStr: '30'))
            skipDefaultCheckout(true)
        }

        stages {
            stage('Matrix Tests') {
                matrix {
                    axes {
                        axis {
                            name 'SOURCE_VERSION_AXIS'
                            values 'ES_5.6', 'ES_7.10'
                        }
                        axis {
                            name 'TARGET_VERSION_AXIS'
                            values 'OS_2.19', 'OS_3.1'
                        }
                    }

                    // Each matrix combination gets its own agent
                    agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }

                    when {
                        expression {
                            // Filter which combinations to run based on parameters
                            (params.SOURCE_VERSION == '(all)' || params.SOURCE_VERSION == SOURCE_VERSION_AXIS) &&
                                    (params.TARGET_VERSION == '(all)' || params.TARGET_VERSION == TARGET_VERSION_AXIS)
                        }
                    }

                    // ALL your pipeline stages go here - each runs on separate workers
                    stages {
                        stage('Checkout') {
                            steps {
                                script {
                                    sh 'sudo chown -R $(whoami) .'
                                    sh 'sudo chmod -R u+w .'
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

                        stage('Test Migration') {
                            steps {
                                timeout(time: 30, unit: 'MINUTES') {
                                    script {
                                        echo "Testing migration from ${SOURCE_VERSION_AXIS} to ${TARGET_VERSION_AXIS}"
                                        // Add your specific test logic here
                                        // This could call different test scripts based on the matrix values
                                        sh "echo 'Running tests for ${SOURCE_VERSION_AXIS} to ${TARGET_VERSION_AXIS}'"
                                    }
                                }
                            }
                        }
                    }

                    // Post actions for each matrix combination
                    post {
                        always {
                            script {
                                echo "Cleanup for ${SOURCE_VERSION_AXIS} -> ${TARGET_VERSION_AXIS}"
                                // Each matrix combination does its own cleanup
                            }
                        }
                        success {
                            script {
                                echo "✅ Success: ${SOURCE_VERSION_AXIS} -> ${TARGET_VERSION_AXIS}"
                            }
                        }
                        failure {
                            script {
                                echo "❌ Failed: ${SOURCE_VERSION_AXIS} -> ${TARGET_VERSION_AXIS}"
                            }
                        }
                    }
                }
            }
        }
    }
}