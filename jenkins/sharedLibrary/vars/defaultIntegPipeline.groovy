// The parent 'vars' folder is not at the root of the repo which presents issues for Jenkins to dynamically load
// this library for a given branch in our pipelines, as Jenkins typically expects a shared library to be its own
// repository. This means that if a branch makes changes to this shared library and also wants to use those changes
// in a given pipeline, the library version declaration will need to be manually updated to that branch. Potentially,
// this issue could be resolved by simply moving this 'vars' folder to the repo root.
def call(Map config = [:]) {
    def source_cdk_context = config.sourceContext
    def migration_cdk_context = config.migrationContext
    if(source_cdk_context == null || source_cdk_context.isEmpty()){
        throw new RuntimeException("The sourceContext argument must be provided");
    }
    if(migration_cdk_context == null || migration_cdk_context.isEmpty()){
        throw new RuntimeException("The migrationContext argument must be provided");
    }
    def source_context_id = config.sourceContextId ?: 'source-single-node-ec2'
    def migration_context_id = config.migrationContextId ?: 'migration-default'
    def gitUrl = config.gitUrl ?: 'https://github.com/opensearch-project/opensearch-migrations.git'
    def gitBranch = config.gitBranch ?: 'main'
    def stageId = config.stageId ?: 'aws-integ'
    def source_context_file_name = 'sourceJenkinsContext.json'
    def migration_context_file_name = 'migrationJenkinsContext.json'
    pipeline {
        agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }

        stages {
            stage('Checkout') {
                steps {
                    git branch: "${gitBranch}", url: "${gitUrl}"
                }
            }

            stage('Test Caller Identity') {
                steps {
                    sh 'aws sts get-caller-identity'
                }
            }

            stage('Setup E2E CDK Context') {
                steps {
                    writeFile (file: "test/$source_context_file_name", text: source_cdk_context)
                    sh "echo 'Using source context file options: ' && cat test/$source_context_file_name"
                    writeFile (file: "test/$migration_context_file_name", text: migration_cdk_context)
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
                                    sh "sudo ./awsE2ESolutionSetup.sh --source-context-file './$source_context_file_name' --migration-context-file './$migration_context_file_name' --source-context-id $source_context_id --migration-context-id $migration_context_id --stage ${stageId} --migrations-git-url ${gitUrl} --migrations-git-branch ${gitBranch}"
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
                                    sh "sudo ./awsRunIntegTests.sh --stage ${stageId} --migrations-git-url ${gitUrl} --migrations-git-branch ${gitBranch} --unique-id ${uniqueId}"
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
                                sh "sudo ./awsE2ESolutionSetup.sh --stage ${stageId} --run-post-actions"
                            }
                        }
                    }
                }
            }
        }
    }
}
