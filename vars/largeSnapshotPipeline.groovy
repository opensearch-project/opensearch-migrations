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
    def skipSourceDeploy = config.skipSourceDeploy ?: false
    def testUniqueId = config.testUniqueId ?: "integ_full_${time}_${currentBuild.number}"
    def testDir = "/root/lib/integ_test/integ_test"
    def integTestCommand = config.integTestCommand ?: "${testDir}/replayer_tests.py"
    pipeline {
        agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }

        parameters {
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/jugal-chauhan/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: 'test-k8s-large-snapshot', description: 'Git branch to use for repository')
            string(name: 'STAGE', defaultValue: "${defaultStageId}", description: 'Stage name for deployment environment')
            string(name: 'NUM_SHARDS', defaultValue: '10', description: 'Number of index shards')
            string(name: 'MULTIPLICATION_FACTOR', defaultValue: '1000', description: 'Document multiplication factor')
            string(name: 'BATCH_COUNT', defaultValue: '3', description: 'Number of batches')
            string(name: 'DOCS_PER_BATCH', defaultValue: '100', description: 'Documents per batch')
            string(name: 'BACKFILL_TIMEOUT_HOURS', defaultValue: '45', description: 'Backfill timeout in hours')
            string(name: 'LARGE_SNAPSHOT_RATE_MB_PER_NODE', defaultValue: '2000', description: 'Rate for large snapshot creation in MB per node')
            string(name: 'RFS_WORKERS', defaultValue: '8', description: 'Number of RFS workers to scale to')
            choice(name: 'CLUSTER_VERSION', choices: ['es5x', 'es6x', 'es7x', 'os2x'], description: 'Target cluster version for data format')
        }

        environment {
            NUM_SHARDS = "${params.NUM_SHARDS}"
            MULTIPLICATION_FACTOR = "${params.MULTIPLICATION_FACTOR}"
            BATCH_COUNT = "${params.BATCH_COUNT}"
            DOCS_PER_BATCH = "${params.DOCS_PER_BATCH}"
            BACKFILL_TIMEOUT_HOURS = "${params.BACKFILL_TIMEOUT_HOURS}"
            LARGE_SNAPSHOT_RATE_MB_PER_NODE = "${params.LARGE_SNAPSHOT_RATE_MB_PER_NODE}"
            RFS_WORKERS = "${params.RFS_WORKERS}"
            CLUSTER_VERSION = "${params.CLUSTER_VERSION}"
        }

        options {
            // Acquire lock on a fixed resource name but store the stage parameter
            lock(resource: "${params.STAGE}", variable: 'lockId')
            timeout(time: 30, unit: 'HOURS')
            buildDiscarder(logRotator(daysToKeepStr: '30'))
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
                                sh 'sudo --preserve-env ./gradlew clean build --no-daemon'
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
                                    echo "Acquired deployment stage: ${params.STAGE}"
                                    sh 'sudo usermod -aG docker $USER'
                                    sh 'sudo newgrp docker'
                                    def baseCommand = "sudo --preserve-env ./awsE2ESolutionSetup.sh --source-context-file './$source_context_file_name' " +
                                            "--migration-context-file './$migration_context_file_name' " +
                                            "--source-context-id $source_context_id " +
                                            "--migration-context-id $migration_context_id " +
                                            "--stage ${params.STAGE} " +
                                            "--migrations-git-url ${params.GIT_REPO_URL} " +
                                            "--migrations-git-branch ${params.GIT_BRANCH}"
                                    if (skipCaptureProxyOnNodeSetup) {
                                        baseCommand += " --skip-capture-proxy"
                                    }
                                    if (skipSourceDeploy) {
                                        baseCommand += " --skip-source-deploy"
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

            stage('Integ Tests') {
                steps {
                    timeout(time: 24, unit: 'HOURS') {
                        dir('test') {
                            script {
                                // Allow overwriting this step
                                if (config.integTestStep) {
                                    config.integTestStep()
                                } else {
                                    echo "Running with NUM_SHARDS=${env.NUM_SHARDS}, MULTIPLICATION_FACTOR=${env.MULTIPLICATION_FACTOR}, BATCH_COUNT=${env.BATCH_COUNT}, DOCS_PER_BATCH=${env.DOCS_PER_BATCH}, BACKFILL_TIMEOUT_HOURS=${env.BACKFILL_TIMEOUT_HOURS}"
                                    def test_result_file = "${testDir}/reports/${testUniqueId}/report.xml"
                                    def populatedIntegTestCommand = integTestCommand.replaceAll("<STAGE>", params.STAGE)
                                    def command = "pipenv run pytest --log-file=${testDir}/reports/${testUniqueId}/pytest.log " +
                                            "--num_shards=${env.NUM_SHARDS} " +
                                            "--multiplication_factor=${env.MULTIPLICATION_FACTOR} " +
                                            "--batch_count=${env.BATCH_COUNT} " +
                                            "--docs_per_batch=${env.DOCS_PER_BATCH} " +
                                            "--backfill_timeout_hours=${env.BACKFILL_TIMEOUT_HOURS} " +
                                            "--large_snapshot_rate_mb_per_node=${env.LARGE_SNAPSHOT_RATE_MB_PER_NODE} " +
                                            "--junitxml=${test_result_file} ${populatedIntegTestCommand} " +
                                            "--unique_id ${testUniqueId} " +
                                            "--stage ${params.STAGE} " +
                                            "--rfs_workers ${env.RFS_WORKERS} " +
                                            "--cluster_version ${env.CLUSTER_VERSION} " +
                                            "-s"
                                    withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                        withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", duration: 3600, roleSessionName: 'jenkins-session') {
                                            sh "sudo --preserve-env ./awsRunIntegTests.sh --command '${command}' " +
                                                    "--test-result-file ${test_result_file} " +
                                                    "--stage ${params.STAGE}"
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            stage('Cleanup Deployment') {
                steps {
                    timeout(time: 1, unit: 'HOURS') {
                        dir('test') {
                            script {
                                // Allow overwriting this step
                                if (config.cleanupStep) {
                                    config.cleanupStep()
                                } else {
                                    echo "Cleaning up all deployed stacks on stage: ${params.STAGE}"
                                    dir('cleanupDeployment') {
                                        sh "sudo --preserve-env pipenv install --deploy --ignore-pipfile"
                                        def command = "pipenv run python3 cleanup_deployment.py --stage ${params.STAGE}"
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

        post {
            always {
                timeout(time: 10, unit: 'MINUTES') {
                    dir('test') {
                        script {
                            echo "Pipeline execution complete"
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
