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
    def source_context_id = config.sourceContextId ?: 'source-single-node-ec2'
    def migration_context_id = config.migrationContextId ?: 'migration-default'
    def source_context_file_name = 'sourceJenkinsContext.json'
    def migration_context_file_name = 'migrationJenkinsContext.json'
    def skipCaptureProxyOnNodeSetup = config.skipCaptureProxyOnNodeSetup ?: false
    def time = new Date().getTime()
    def testUniqueId = config.testUniqueId ?: "integ_full_${time}_${currentBuild.number}"
    def testDir = "/root/lib/integ_test/integ_test"
    def integTestCommand = config.integTestCommand ?: "${testDir}/replayer_tests.py"
    pipeline {
        agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }

        parameters {
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/opensearch-project/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Git branch to use for repository')
            string(name: 'STAGE', defaultValue: "${defaultStageId}", description: 'Stage name for deployment environment')
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
                        // Allow overwriting this step
                        if (config.checkoutStep) {
                            config.checkoutStep()
                        } else {
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
                                sh './gradlew clean build --no-daemon --stacktrace'
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
                                            "--stage ${stage} " +
                                            "--migrations-git-url ${params.GIT_REPO_URL} " +
                                            "--migrations-git-branch ${params.GIT_BRANCH}"
                                    if (skipCaptureProxyOnNodeSetup) {
                                        baseCommand += " --skip-capture-proxy"
                                    }
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
