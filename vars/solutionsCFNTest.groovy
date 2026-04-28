def call(Map config = [:]) {
    def jobName = config.jobName ?: "solutionsCFNTest"
    def defaultGitBranch = config.defaultGitBranch ?: 'main'

    pipeline {
        agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }

        parameters {
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/opensearch-project/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: defaultGitBranch, description: 'Git branch to use for repository')
            string(name: 'GIT_COMMIT', defaultValue: '', description: '(Optional) Specific commit to checkout after cloning branch')
            string(name: 'STAGE', defaultValue: "sol-integ", description: 'Stage name for deployment environment')
            string(name: 'REGION', defaultValue: 'us-east-1', description: 'AWS region for deployment')
            string(name: 'VERSION', defaultValue: '', description: 'Release version to deploy (e.g. "2.9.0"). When set, checks out the release tag instead of GIT_BRANCH.')
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
                            [key: 'GIT_COMMIT', value: '$.GIT_COMMIT'],
                            [key: 'job_name', value: '$.job_name']
                    ],
                    tokenCredentialId: 'jenkins-migrations-generic-webhook-token',
                    causeString: 'Triggered by PR on opensearch-migrations repository',
                    regexpFilterExpression: "^$jobName\$",
                    regexpFilterText: "\$job_name",
            )
            cron(periodicCron(jobName))
        }

        stages {
            stage('Checkout') {
                steps {
                    script {
                        def checkoutBranch = params.VERSION?.trim() ? params.VERSION : params.GIT_BRANCH
                        echo """
                            ================================================================
                            Solutions CFN Test
                            ================================================================
                            Git:                    ${params.GIT_REPO_URL} @ ${checkoutBranch}
                            Stage:                  ${params.STAGE}
                            Region:                 ${params.REGION}
                            Version:                ${params.VERSION ?: 'N/A (using GIT_BRANCH)'}
                            ================================================================
                        """
                        checkoutStep(branch: checkoutBranch, repo: params.GIT_REPO_URL, commit: params.GIT_COMMIT)
                    }
                }
            }

            stage('Deployment') {
                steps {
                    timeout(time: 15, unit: 'MINUTES') {
                        dir('deployment/migration-assistant-solution') {
                            script {
                                env.STACK_NAME_SUFFIX = "${stage}-${params.REGION}"
                                sh "npm install"
                                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", region: params.REGION, duration: 3600, roleSessionName: 'jenkins-session') {
                                        sh "npx cdk deploy Migration-Assistant-Infra-Create-VPC-${env.STACK_NAME_SUFFIX} --parameters Stage=${stage} --require-approval never --concurrency 3"
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
                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", region: params.REGION, duration: 3600, roleSessionName: 'jenkins-session') {
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
                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", region: params.REGION, duration: 3600, roleSessionName: 'jenkins-session') {
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
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", region: params.REGION, duration: 3600, roleSessionName: 'jenkins-session') {
                                    sh "npx cdk destroy Migration-Assistant-Infra-Create-VPC-${env.STACK_NAME_SUFFIX} --force"
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
