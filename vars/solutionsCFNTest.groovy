def call(Map config = [:]) {

    pipeline {
        agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }

        parameters {
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/lewijacn/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: 'sol-pipeline', description: 'Git branch to use for repository')
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
                                sh "sudo npm install"
                                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", region: "us-east-1", duration: 3600, roleSessionName: 'jenkins-session') {
                                        sh "sudo --preserve-env cdk deploy 'Migration-Assistant-Infra-Create-VPC'--require-approval never --concurrency 3"
                                    }
                                }
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
                                        sh "sudo --preserve-env ./awsRunInitBootstrap.sh "
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
                    dir('test/cleanupDeployment') {
                        script {
                            sh "sudo --preserve-env pipenv install --deploy --ignore-pipfile"
                            def command = "pipenv run python3 cleanup_deployment.py --stage ${stage}"
                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", region: "us-east-1", duration: 3600, roleSessionName: 'jenkins-session') {
                                    sh "sudo --preserve-env ${command}"
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
