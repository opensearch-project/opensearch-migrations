def call(Map config = [:]) {
    def jobName = config.jobName ?: "solutionsCFNTest"

    pipeline {
        agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }

        parameters {
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/opensearch-project/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Git branch to use for repository')
            string(name: 'STAGE', defaultValue: "sol-integ", description: 'Stage name for deployment environment')
        }

        options {
            // Acquire lock on a given deployment stage
            lock(label: params.STAGE, quantity: 1, variable: 'stage')
            timeout(time: 1, unit: 'HOURS')
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

            stage('Deployment') {
                steps {
                    timeout(time: 15, unit: 'MINUTES') {
                        dir('deployment/migration-assistant-solution') {
                            script {
                                env.STACK_NAME_SUFFIX = "${stage}-us-east-1"
                                sh "npm install"
                                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", region: "us-east-1", duration: 3600, roleSessionName: 'jenkins-session') {
                                        sh "cdk deploy Migration-Assistant-Infra-Create-VPC-${env.STACK_NAME_SUFFIX} --parameters Stage=${stage} --require-approval never --concurrency 3"
                                    }
                                }
                                // Wait for instance to be ready to accept SSM commands
                                sh "sleep 15"
                            }
                        }
                    }
                }
            }

            stage('Init Bootstrap') {
                steps {
                    timeout(time: 90, unit: 'MINUTES') {
                        dir('test') {
                            script {
                                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", region: "us-east-1", duration: 3600, roleSessionName: 'jenkins-session') {
                                        sh "./awsRunInitBootstrap.sh --stage ${stage} --log-group-name solutions-deployment-jenkins-pipeline-${stage} --workflow INIT_BOOTSTRAP"
                                    }
                                }
                            }
                        }
                    }
                }
            }

            stage('Verify Bootstrap Instance') {
                steps {
                    timeout(time: 30, unit: 'MINUTES') {
                        dir('test') {
                            script {
                                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", region: "us-east-1", duration: 3600, roleSessionName: 'jenkins-session') {
                                        sh "./awsRunInitBootstrap.sh --stage ${stage} --log-group-name solutions-deployment-jenkins-pipeline-${stage} --workflow VERIFY_INIT_BOOTSTRAP"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        post {
            always {
                timeout(time: 30, unit: 'MINUTES') {
                    dir('deployment/migration-assistant-solution') {
                        script {
                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", region: "us-east-1", duration: 3600, roleSessionName: 'jenkins-session') {
                                    sh "cdk destroy Migration-Assistant-Infra-Create-VPC-${env.STACK_NAME_SUFFIX} --force"
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
