def call(Map config = [:]) {
    def sourceContext = config.sourceContext
    def migrationContext = config.migrationContext
    def defaultStageId = config.defaultStageId
    def jobName = config.jobName
    if(sourceContext == null || sourceContext.isEmpty()){
        throw new RuntimeException("The sourceContext argument must be provided");
    }
    if(migrationContext == null || migrationContext.isEmpty()){
        throw new RuntimeException("The migrationContext argument must be provided");
    }
    if(defaultStageId == null || defaultStageId.isEmpty()){
        throw new RuntimeException("The defaultStageId argument must be provided");
    }
    if(jobName == null || jobName.isEmpty()){
        throw new RuntimeException("The jobName argument must be provided");
    }
    def defaultGitBranch = config.defaultGitBranch ?: 'main'
    def source_context_id = config.sourceContextId ?: 'source-single-node-ec2'
    def migration_context_id = config.migrationContextId ?: 'migration-default'
    def source_context_file_name = 'sourceJenkinsContext.json'
    def migration_context_file_name = 'migrationJenkinsContext.json'
    def time = new Date().getTime()
    def testUniqueId = config.testUniqueId ?: "integ_full_${time}_${currentBuild.number}"
    def testDir = "/root/lib/integ_test/integ_test"
    def integTestCommand = config.integTestCommand ?: "${testDir}/replayer_tests.py"
    pipeline {
        agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }

        parameters {
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/opensearch-project/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: defaultGitBranch, description: 'Git branch to use for repository')
            string(name: 'GIT_COMMIT', defaultValue: '', description: '(Optional) Specific commit to checkout after cloning branch')
            string(name: 'STAGE', defaultValue: "${defaultStageId}", description: 'Stage name for deployment environment')
            string(name: 'VERSION', defaultValue: '', description: 'Release version to deploy (e.g. "2.9.0"). When set, checks out the release tag instead of GIT_BRANCH.')
        }

        options {
            // Acquire lock on a given deployment stage
            lock(label: params.STAGE, quantity: 1, variable: 'stage')
            timeout(time: 3, unit: 'HOURS')
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
                        env.CHECKOUT_BRANCH = checkoutBranch
                        echo """
                            ================================================================
                            Default Integration Pipeline
                            ================================================================
                            Git:                    ${params.GIT_REPO_URL} @ ${checkoutBranch}
                            Stage:                  ${params.STAGE}
                            Version:                ${params.VERSION ?: 'N/A (using GIT_BRANCH)'}
                            ================================================================
                        """
                        // Allow overwriting this step
                        if (config.checkoutStep) {
                            config.checkoutStep()
                        } else {
                            checkoutStep(branch: checkoutBranch, repo: params.GIT_REPO_URL, commit: params.GIT_COMMIT)
                        }
                    }
                }
            }

            stage('Test Caller Identity') {
                steps {
                    script {
                        // Allow overwriting this step
                        if (config.awsIdentityCheckStep) {
                            config.awsIdentityCheckStep()
                        } else {
                            sh 'aws sts get-caller-identity'
                        }
                    }
                }
            }

            stage('Setup E2E CDK Context') {
                steps {
                    script {
                        // Allow overwriting this step
                        if (config.cdkContextStep) {
                            config.cdkContextStep()
                        } else {
                            writeFile (file: "test/$source_context_file_name", text: sourceContext)
                            sh "echo 'Using source context file options: ' && cat test/$source_context_file_name"
                            writeFile (file: "test/$migration_context_file_name", text: migrationContext)
                            sh "echo 'Using migration context file options: ' && cat test/$migration_context_file_name"
                        }
                    }
                }
            }

            stage('Build') {
                steps {
                    timeout(time: 1, unit: 'HOURS') {
                        script {
                            // Allow overwriting this step
                            if (config.buildStep) {
                                config.buildStep()
                            } else {
                                sh './gradlew clean build -x test --no-daemon --stacktrace'
                            }
                        }
                    }
                }
            }

            stage('Pre-Deploy Cleanup') {
                when {
                    expression { config.preDeployStep != null }
                }
                steps {
                    timeout(time: 60, unit: 'MINUTES') {
                        dir('test') {
                            script {
                                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", duration: 5400, roleSessionName: 'jenkins-session') {
                                        config.preDeployStep(
                                            stage: stage,
                                            sourceContextFileName: source_context_file_name,
                                            migrationContextFileName: migration_context_file_name,
                                            sourceContextId: source_context_id,
                                            migrationContextId: migration_context_id
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            stage('Deploy') {
                steps {
                    timeout(time: 90, unit: 'MINUTES') {
                        dir('test') {
                            script {
                                // Allow overwriting this step
                                if (config.deployStep) {
                                    config.deployStep()
                                } else {
                                    echo "Acquired deployment stage: ${stage}"
                                    def baseCommand = "./awsE2ESolutionSetup.sh --source-context-file './$source_context_file_name' " +
                                            "--migration-context-file './$migration_context_file_name' " +
                                            "--source-context-id $source_context_id " +
                                            "--migration-context-id $migration_context_id " +
                                            "--stage ${stage}"
                                    withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                        withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", duration: 5400, roleSessionName: 'jenkins-session') {
                                            sh baseCommand
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            stage('Pre-Integ Test Cleanup') {
                steps {
                    timeout(time: 10, unit: 'MINUTES') {
                        dir('test') {
                            script {
                                if (config.preIntegTestStep) {
                                    withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                        withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", duration: 3600, roleSessionName: 'jenkins-session') {
                                            config.preIntegTestStep(stage)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            stage('Integ Tests') {
                steps {
                    timeout(time: 1, unit: 'HOURS') {
                        dir('test') {
                            script {
                                // Allow overwriting this step
                                if (config.integTestStep) {
                                    config.integTestStep()
                                } else {
                                    def test_result_file = "${testDir}/reports/${testUniqueId}/report.xml"
                                    def populatedIntegTestCommand = integTestCommand.replaceAll("<STAGE>", stage)
                                    def command = "pipenv run pytest --log-file=${testDir}/reports/${testUniqueId}/pytest.log " +
                                            "--junitxml=${test_result_file} ${populatedIntegTestCommand} " +
                                            "--unique_id ${testUniqueId} " +
                                            "--stage ${stage} " +
                                            "-s"
                                    withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                        withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", duration: 3600, roleSessionName: 'jenkins-session') {
                                            sh "./awsRunIntegTests.sh --command '${command}' " +
                                                    "--test-result-file ${test_result_file} " +
                                                    "--stage ${stage}"
                                        }
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
                timeout(time: 10, unit: 'MINUTES') {
                    dir('test') {
                        script {
                            // Allow overwriting this step
                            if (config.finishStep) {
                                config.finishStep()
                            } else {
                                sh "echo 'Default post step performs no actions'"
                            }
                        }
                    }
                }
            }
        }
    }
}
