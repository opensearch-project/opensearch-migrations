def call(Map config = [:]) {
    def sourceContext = config.sourceContext
    def migrationContext = config.migrationContext
    def defaultStageId = config.defaultStageId
    if(sourceContext == null || sourceContext.isEmpty()){
        throw new RuntimeException("The sourceContext argument must be provided");
    }
    if(migrationContext == null || migrationContext.isEmpty()){
        throw new RuntimeException("The migrationContext argument must be provided");
    }
    if(defaultStageId == null || defaultStageId.isEmpty()){
        throw new RuntimeException("The migrationContext argument must be provided");
    }
    def source_context_id = config.sourceContextId ?: 'source-single-node-ec2'
    def migration_context_id = config.migrationContextId ?: 'migration-default'
    def source_context_file_name = 'sourceJenkinsContext.json'
    def migration_context_file_name = 'migrationJenkinsContext.json'
    def skipCaptureProxyOnNodeSetup = config.skipCaptureProxyOnNodeSetup ?: false
    pipeline {
        agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }

        parameters {
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/opensearch-project/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Git branch to use for repository')
            string(name: 'STAGE', defaultValue: "${defaultStageId}", description: 'Stage name for deployment environment')
            string(name: 'VPC_ID', description: 'VPC to use for deployments')
        }

        stages {
            stage('Checkout') {
                steps {
                    git branch: "${params.GIT_BRANCH}", url: "${params.GIT_REPO_URL}"
                }
            }

            stage('Test Caller Identity') {
                steps {
                    sh 'aws sts get-caller-identity'
                }
            }

            stage('Setup E2E CDK Context') {
                steps {
                    writeFile (file: "test/$source_context_file_name", text: sourceContext)
                    sh "echo 'Using source context file options: ' && cat test/$source_context_file_name"
                    writeFile (file: "test/$migration_context_file_name", text: migrationContext)
                    sh "echo 'Using migration context file options: ' && cat test/$migration_context_file_name"
                }
            }

            stage('Build') {
                steps {
                    timeout(time: 1, unit: 'HOURS') {
                        sh 'sudo ./gradlew clean build'
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
                                    sh 'sudo usermod -aG docker $USER'
                                    sh 'sudo newgrp docker'
                                    def baseCommand = "sudo ./awsE2ESolutionSetup.sh --source-context-file './$source_context_file_name' --migration-context-file './$migration_context_file_name' --source-context-id $source_context_id --migration-context-id $migration_context_id --stage ${params.STAGE} --migrations-git-url ${params.GIT_REPO_URL} --migrations-git-branch ${params.GIT_BRANCH}"
                                    if (skipCaptureProxyOnNodeSetup) {
                                        baseCommand += " --skip-capture-proxy"
                                    }
                                    sh baseCommand
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
                                    def time = new Date().getTime()
                                    def uniqueId = "integ_min_${time}_${currentBuild.number}"
                                    def test_dir = "/root/lib/integ_test/integ_test"
                                    def test_result_file = "${test_dir}/reports/${uniqueId}/report.xml"
                                    def command = "pytest --log-file=${test_dir}/reports/${uniqueId}/pytest.log --junitxml=${test_result_file} ${test_dir}/replayer_tests.py --unique_id ${uniqueId} -s"
                                    sh "sudo ./awsRunIntegTests.sh --command '${command}' --test-result-file ${test_result_file} --stage ${params.STAGE}"
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
