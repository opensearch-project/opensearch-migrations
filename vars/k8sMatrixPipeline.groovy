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
                    choices: ['(all)', 'OS_2.19', 'OS_3.1'],
                    description: 'Pick a specific target version, or "(all)"'
            )
        }

        options {
            timeout(time: 2, unit: 'HOURS')
            buildDiscarder(logRotator(daysToKeepStr: '30'))
            skipDefaultCheckout(true)
        }

        stages {
            stage('Argo Integration Tests') {
                matrix {
                    axes {
                        axis {
                            name 'SRC'
                            values 'ES_5.6', 'ES_7.10'
                        }
                        axis {
                            name 'TGT'
                            values 'OS_2.19', 'OS_3.1'
                        }
                    }

                    // Each matrix combination gets its own agent
                    agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }

                    when {
                        expression {
                            // Filter which combinations to run based on parameters
                            (params.SOURCE_VERSION == '(all)' || params.SOURCE_VERSION == SRC) &&
                                    (params.TARGET_VERSION == '(all)' || params.TARGET_VERSION == TGT)
                        }
                    }

                    // Single stage combining all steps
                    stages {
                        stage('Migration Test') {
                            steps {
                                timeout(time: 1, unit: 'HOURS') {
                                    script {
                                        echo "🚀 Starting migration test: ${SRC} → ${TGT}"
                                        currentBuild.description = "${SRC} → ${TGT}"
                                        
                                        // CHECKOUT PHASE
                                        echo "📥 Checkout phase..."
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
                                        echo "✅ Checkout completed for ${SRC} → ${TGT}"
                                        
                                        // BUILD PHASE (if needed)
                                        echo "🔨 Build phase..."
                                        // Uncomment if you need to build
                                        // sh './gradlew clean build --no-daemon --stacktrace'
                                        echo "✅ Build completed for ${SRC} → ${TGT}"
                                        
                                        // TEST PHASE
                                        echo "🧪 Test phase..."
                                        // Add your specific test logic here
                                        // This could call different test scripts based on the matrix values
                                        sh "echo 'Running migration tests for ${SRC} to ${TGT}'"
                                        echo "✅ Tests completed for ${SRC} → ${TGT}"
                                        
                                        echo "🎉 Migration test completed successfully: ${SRC} → ${TGT}"
                                    }
                                }
                            }
                        }
                    }

                    // Post actions for each matrix combination
                    post {
                        always {
                            script {
                                echo "Cleanup for ${SRC} → ${TGT}"
                                // Each matrix combination does its own cleanup
                            }
                        }
                        success {
                            script {
                                echo "✅ Success: ${SRC} → ${TGT}"
                            }
                        }
                        failure {
                            script {
                                echo "❌ Failed: ${SRC} → ${TGT}"
                            }
                        }
                    }
                }
            }
        }
    }
}
