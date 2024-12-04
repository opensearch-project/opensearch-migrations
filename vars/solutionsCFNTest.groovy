def call(Map config = [:]) {

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
        }

        stages {
            stage('Checkout') {
                steps {
                    script {
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
                                sh "sudo npm install"
                                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", region: "us-east-1", duration: 3600, roleSessionName: 'jenkins-session') {
                                        sh "sudo --preserve-env cdk deploy Migration-Assistant-Infra-Create-VPC-${env.STACK_NAME_SUFFIX} --parameters Stage=${stage} --require-approval never --concurrency 3"
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
                    timeout(time: 30, unit: 'MINUTES') {
                        dir('test') {
                            script {
                                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", region: "us-east-1", duration: 3600, roleSessionName: 'jenkins-session') {
                                        sh "sudo --preserve-env ./awsRunInitBootstrap.sh --stage ${stage} --workflow INIT_BOOTSTRAP"
                                    }
                                }
                            }
                        }
                    }
                }
            }

            stage('Verify Bootstrap Instance') {
                steps {
                    timeout(time: 5, unit: 'MINUTES') {
                        dir('test') {
                            script {
                                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", region: "us-east-1", duration: 3600, roleSessionName: 'jenkins-session') {
                                        sh "sudo --preserve-env ./awsRunInitBootstrap.sh --stage ${stage} --workflow VERIFY_INIT_BOOTSTRAP"
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
                                    sh "sudo --preserve-env cdk destroy Migration-Assistant-Infra-Create-VPC-${env.STACK_NAME_SUFFIX} --force"
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
