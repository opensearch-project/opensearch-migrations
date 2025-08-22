def call(Map config = [:]) {
    pipeline {
        parameters {
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/lewijacn/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: 'k8s-matrix-test', description: 'Git branch to use for repository')
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

//        triggers {
//            cron('@hourly')
//        }
        matrix {
            axes {
                axis {
                    name 'SOURCE_VERSION_AXIS'
                    values 'ES_5.6'
                }
                axis {
                    name 'TARGET_VERSION_AXIS'
                    values 'OS_2.19'
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
            }
        }
    }
}