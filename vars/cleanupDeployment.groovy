def call(Map config = [:]) {

    pipeline {
        agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }

        parameters {
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/opensearch-project/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Git branch to use for repository')
            string(name: 'STAGE', description: 'Deployment stage name in group to delete (e.g. rfs-integ1)')
        }

        options {
            // Acquire lock on a given deployment stage
            lock(resource: params.STAGE, variable: 'stage')
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

            stage('Cleanup Deployment') {
                steps {
                    timeout(time: 1, unit: 'HOURS') {
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
}
