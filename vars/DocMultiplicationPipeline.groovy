def call(Map config = [:]) {
    def params = config.params ?: [:]
    def contexts = config.contexts ?: [:]
    def workerAgent = config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host'
    
    node(workerAgent) {
        try {
            echo "Starting Document Multiplication Pipeline"
            echo "Target: Create large snapshot in S3 bucket: ${params.snapshotBucketPrefix}${params.stage}"
            
            env.AWS_DEFAULT_REGION = "${params.region}"
            env.STAGE = "${params.stage}"
            env.DEBUG_MODE = "${params.debugMode}"
            
            stage('1. Checkout') {
                echo "Stage 1: Checking out repository"
                echo "Repository: ${params.gitRepoUrl}"
                echo "Branch: ${params.gitBranch}"
                
                sh 'sudo chown -R $(whoami) . || true'
                sh 'sudo chmod -R u+w . || true'
                
                if (sh(script: 'git rev-parse --git-dir > /dev/null 2>&1', returnStatus: true) == 0) {
                    sh 'git reset --hard && git clean -fd'
                }
                
                git branch: "${params.gitBranch}", url: "${params.gitRepoUrl}"
                
                echo "Repository checked out successfully"
            }
            
            // Stage 2: Test Caller Identity
            stage('2. Test Caller Identity') {
                echo "Stage 2: Verifying AWS credentials"
                
                sh 'aws sts get-caller-identity'
                
                echo "AWS credentials verified"
            }
            
            // Stage 3: Build
            stage('3. Build') {
                timeout(time: 1, unit: 'HOURS') {
                    echo "Stage 3: Building project"
                    
                    sh './gradlew clean build --no-daemon --stacktrace'
                    
                    echo "Project built successfully"
                }
            }
            
            // Stage 4: Create Source Cluster
            stage('4. Create Source Cluster') {
                timeout(time: 60, unit: 'MINUTES') {
                    echo "Stage 4: Creating source cluster"
                    echo "Cluster Version: ${params.clusterVersion} (${params.engineVersion})"
                    echo "Region: ${params.region}"
                    
                    dir('test') {
                        // Write source context to file
                        writeJSON file: 'sourceJenkinsContext.json', json: contexts.source
                        
                        if (params.debugMode) {
                            sh "echo 'Source Context:' && cat sourceJenkinsContext.json"
                        }
                        
                        // Use the new decoupled source cluster setup script and capture output
                        def command = "./awsSourceClusterSetup.sh " +
                            "--cluster-version ${params.engineVersion} " +
                            "--stage ${params.stage} " +
                            "--region ${params.region}"
                        
                        def clusterOutput = ""
                        withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                            withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", duration: 3600, roleSessionName: 'jenkins-session') {
                                clusterOutput = sh(script: command, returnStdout: true)
                            }
                        }
                        
                        // Parse cluster information from output and set environment variables
                        echo "Parsing cluster information from Stage 4 output..."
                        def endpointMatch = clusterOutput =~ /CLUSTER_ENDPOINT=(.+)/
                        def vpcMatch = clusterOutput =~ /VPC_ID=(.+)/
                        def domainMatch = clusterOutput =~ /DOMAIN_NAME=(.+)/
                        
                        if (endpointMatch) {
                            env.SOURCE_CLUSTER_ENDPOINT = endpointMatch[0][1].trim()
                            echo "Captured SOURCE_CLUSTER_ENDPOINT: ${env.SOURCE_CLUSTER_ENDPOINT}"
                        } else {
                            error("Failed to extract CLUSTER_ENDPOINT from Stage 4 output")
                        }
                        
                        if (vpcMatch) {
                            env.SOURCE_VPC_ID = vpcMatch[0][1].trim()
                            echo "Captured SOURCE_VPC_ID: ${env.SOURCE_VPC_ID}"
                        } else {
                            error("Failed to extract VPC_ID from Stage 4 output")
                        }
                        
                        if (domainMatch) {
                            env.SOURCE_DOMAIN_NAME = domainMatch[0][1].trim()
                            echo "Captured SOURCE_DOMAIN_NAME: ${env.SOURCE_DOMAIN_NAME}"
                        }
                        
                        // Validate captured values
                        if (!env.SOURCE_CLUSTER_ENDPOINT || env.SOURCE_CLUSTER_ENDPOINT.isEmpty()) {
                            error("Invalid SOURCE_CLUSTER_ENDPOINT captured from Stage 4")
                        }
                        if (!env.SOURCE_VPC_ID || env.SOURCE_VPC_ID.isEmpty()) {
                            error("Invalid SOURCE_VPC_ID captured from Stage 4")
                        }
                    }
                    
                    echo "Source cluster created successfully"
                    echo "Cluster information captured in environment variables"
                }
            }
            
            // Stage 5: Deploy Migration Infrastructure
            stage('5. Deploy Migration Infrastructure') {
                timeout(time: 60, unit: 'MINUTES') {
                    echo "Stage 5: Deploying migration infrastructure"
                    
                    dir('test') {
                        // Use cluster information captured from Stage 4 environment variables
                        def sourceEndpoint = env.SOURCE_CLUSTER_ENDPOINT
                        def vpcId = env.SOURCE_VPC_ID
                        
                        echo "Using cluster information from Stage 4 environment variables:"
                        echo "Source Endpoint: ${sourceEndpoint}"
                        echo "VPC ID: ${vpcId}"
                        
                        // Validate that we have the required values from Stage 4
                        if (!sourceEndpoint || sourceEndpoint.isEmpty()) {
                            error("SOURCE_CLUSTER_ENDPOINT not available from Stage 4. Check Stage 4 execution.")
                        }
                        if (!vpcId || vpcId.isEmpty()) {
                            error("SOURCE_VPC_ID not available from Stage 4. Check Stage 4 execution.")
                        }
                        
                        // Additional validation for proper format
                        if (!sourceEndpoint.startsWith("https://")) {
                            error("Invalid SOURCE_CLUSTER_ENDPOINT format: ${sourceEndpoint}")
                        }
                        if (!vpcId.startsWith("vpc-")) {
                            error("Invalid SOURCE_VPC_ID format: ${vpcId}")
                        }
                        
                        // Use the new decoupled migration infrastructure setup script
                        def command = "./awsMigrationInfraSetup.sh " +
                            "--source-endpoint ${sourceEndpoint} " +
                            "--source-version ${params.engineVersion} " +
                            "--vpc-id ${vpcId} " +
                            "--stage ${params.stage} " +
                            "--region ${params.region}"
                        
                        echo "Executing migration infrastructure deployment with command:"
                        echo "${command}"
                        
                        withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                            withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", duration: 3600, roleSessionName: 'jenkins-session') {
                                sh command
                            }
                        }
                    }
                    
                    echo "Migration infrastructure deployed successfully"
                }
            }
            
            // Stage 6: CleanUpAndPrepare
            stage('6. CleanUpAndPrepare') {
                timeout(time: 30, unit: 'MINUTES') {
                    echo "Stage 6: CleanUpAndPrepare - Setting up initial test data"
                    echo "Index Name: ${params.indexName}"
                    echo "Number of Shards: ${params.numShards}"
                    
                    dir('test') {
                        // Execute CleanUpAndPrepare module with bash -c wrapper for proper shell context
                        def command = "bash -c \"source /.venv/bin/activate && " +
                            "export INDEX_NAME='${params.indexName}' && " +
                            "export NUM_SHARDS='${params.numShards}' && " +
                            "export DOCS_PER_BATCH='${params.docsPerBatch}' && " +
                            "export MULTIPLICATION_FACTOR='${params.multiplicationFactor}' && " +
                            "export RFS_WORKERS='${params.rfsWorkers}' && " +
                            "export STAGE='${params.stage}' && " +
                            "export SNAPSHOT_REGION='${params.region}' && " +
                            "export LARGE_SNAPSHOT_BUCKET_PREFIX='${params.snapshotBucketPrefix}' && " +
                            "export LARGE_S3_DIRECTORY_PREFIX='${params.s3DirectoryPrefix}' && " +
                            "export CLUSTER_VERSION='${params.clusterVersion}' && " +
                            "export ENGINE_VERSION='${params.engineVersion}' && " +
                            "cd /root/lib/integ_test && " +
                            "python -m integ_test.multiplication_test.CleanUpAndPrepare\""
                        
                        withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                            withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", duration: 1800, roleSessionName: 'jenkins-session') {
                                sh "./awsRunIntegTests.sh --command '${command}' --stage ${params.stage}"
                            }
                        }
                    }
                    
                    echo "CleanUpAndPrepare completed successfully"
                }
            }
            
            // Stage 7: MultiplyDocuments
            stage('7. MultiplyDocuments') {
                timeout(time: 3, unit: 'HOURS') {
                    echo "Stage 7: MultiplyDocuments - Creating large dataset"
                    echo "CORE STAGE: This creates the big dataset for the snapshot"
                    echo "Docs per Batch: ${params.docsPerBatch}"
                    echo "Multiplication Factor: ${params.multiplicationFactor}"
                    echo "RFS Workers: ${params.rfsWorkers}"
                    
                    dir('test') {
                        // Execute MultiplyDocuments module with bash -c wrapper for proper shell context
                        def command = "bash -c \"source /.venv/bin/activate && " +
                            "export INDEX_NAME='${params.indexName}' && " +
                            "export NUM_SHARDS='${params.numShards}' && " +
                            "export DOCS_PER_BATCH='${params.docsPerBatch}' && " +
                            "export MULTIPLICATION_FACTOR='${params.multiplicationFactor}' && " +
                            "export RFS_WORKERS='${params.rfsWorkers}' && " +
                            "export STAGE='${params.stage}' && " +
                            "export SNAPSHOT_REGION='${params.region}' && " +
                            "export LARGE_SNAPSHOT_BUCKET_PREFIX='${params.snapshotBucketPrefix}' && " +
                            "export LARGE_S3_DIRECTORY_PREFIX='${params.s3DirectoryPrefix}' && " +
                            "export CLUSTER_VERSION='${params.clusterVersion}' && " +
                            "export ENGINE_VERSION='${params.engineVersion}' && " +
                            "cd /root/lib/integ_test && " +
                            "python -m integ_test.multiplication_test.MultiplyDocuments\""
                        
                        withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                            withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", duration: 10800, roleSessionName: 'jenkins-session') {
                                sh "./awsRunIntegTests.sh --command '${command}' --stage ${params.stage}"
                            }
                        }
                    }
                    
                    echo "MultiplyDocuments completed - Large dataset created successfully"
                }
            }
            
            // Stage 8: CreateFinalSnapshot
            stage('8. CreateFinalSnapshot') {
                timeout(time: 60, unit: 'MINUTES') {
                    echo "Stage 8: CreateFinalSnapshot - Creating and uploading large snapshot"
                    echo "CORE STAGE: This creates the final large snapshot and uploads to S3"
                    echo "S3 Bucket Prefix: ${params.snapshotBucketPrefix}"
                    echo "S3 Directory Prefix: ${params.s3DirectoryPrefix}"
                    echo "Target S3 Location: ${params.snapshotBucketPrefix}${params.stage}/${params.s3DirectoryPrefix}*"
                    
                    dir('test') {
                        // Execute CreateFinalSnapshot module with bash -c wrapper for proper shell context
                        def command = "bash -c \"source /.venv/bin/activate && " +
                            "export INDEX_NAME='${params.indexName}' && " +
                            "export NUM_SHARDS='${params.numShards}' && " +
                            "export DOCS_PER_BATCH='${params.docsPerBatch}' && " +
                            "export MULTIPLICATION_FACTOR='${params.multiplicationFactor}' && " +
                            "export RFS_WORKERS='${params.rfsWorkers}' && " +
                            "export STAGE='${params.stage}' && " +
                            "export SNAPSHOT_REGION='${params.region}' && " +
                            "export LARGE_SNAPSHOT_BUCKET_PREFIX='${params.snapshotBucketPrefix}' && " +
                            "export LARGE_S3_DIRECTORY_PREFIX='${params.s3DirectoryPrefix}' && " +
                            "export CLUSTER_VERSION='${params.clusterVersion}' && " +
                            "export ENGINE_VERSION='${params.engineVersion}' && " +
                            "cd /root/lib/integ_test && " +
                            "python -m integ_test.multiplication_test.CreateFinalSnapshot\""
                        
                        withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                            withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", duration: 3600, roleSessionName: 'jenkins-session') {
                                sh "./awsRunIntegTests.sh --command '${command}' --stage ${params.stage}"
                            }
                        }
                    }
                    
                    echo "CreateFinalSnapshot completed - Large snapshot uploaded to S3"
                    echo "SUCCESS: Large snapshot available at: ${params.snapshotBucketPrefix}${params.stage}"
                }
            }
            
            // Stage 9: Combined Cleanup
            if (!params.skipCleanup && (currentBuild.result == null || currentBuild.result == 'SUCCESS')) {
                stage('9. Combined Cleanup') {
                    timeout(time: 1, unit: 'HOURS') {
                        echo "Stage 9: Combined Cleanup - Using proven cleanup deployment script"
                        echo "Cleanup Mode: Success-only (preserves resources on failure for debugging)"
                        
                        // First: Use proven cleanup for migration infrastructure
                        dir('test/cleanupDeployment') {
                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", duration: 3600, roleSessionName: 'jenkins-session') {
                                    sh "pipenv install --deploy --ignore-pipfile"
                                    sh "pipenv run python3 cleanup_deployment.py --stage ${params.stage}"
                                }
                            }
                        }
                        
                        // Second: Clean up source cluster (AWS Samples CDK) if migration cleanup didn't handle it
                        dir('test') {
                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", duration: 1800, roleSessionName: 'jenkins-session') {
                                    // Check if source cluster stacks still exist and clean them up
                                    sh """
                                        echo "Checking for remaining source cluster stacks..."
                                        if aws cloudformation describe-stacks --stack-name "OpenSearchDomain-es7x-${params.stage}-${params.region}" --region ${params.region} >/dev/null 2>&1; then
                                            echo "Source cluster stacks still exist, cleaning up using AWS Samples CDK..."
                                            cd tmp/amazon-opensearch-service-sample-cdk
                                            if [ -d "tmp/amazon-opensearch-service-sample-cdk" ]; then
                                                echo "Using existing AWS Samples CDK directory for cleanup"
                                                cdk destroy "*" --force
                                            else
                                                echo "AWS Samples CDK directory not found, using fallback cleanup"
                                                cd ../..
                                                ./awsSourceClusterSetup.sh --cleanup --cluster-version ${params.engineVersion} --stage ${params.stage} --region ${params.region}
                                            fi
                                        else
                                            echo "Source cluster stacks already cleaned up by proven cleanup script"
                                        fi
                                    """
                                }
                            }
                        }
                        
                        echo "Combined cleanup completed successfully using proven cleanup script"
                    }
                }
            }
            
            // Success handling
            echo "SUCCESS: Document Multiplication Test completed successfully!"
            echo ""
            echo "Test Results Summary:"
            echo "  Source Cluster: ${params.clusterVersion} (${params.engineVersion})"
            echo "  Index Created: ${params.indexName} with ${params.numShards} shards"
            echo "  Documents Multiplied: ${params.multiplicationFactor}x factor"
            echo "  Large Snapshot Created: Available in S3"
            echo ""
            echo "S3 Location: ${params.snapshotBucketPrefix}${params.stage}/${params.s3DirectoryPrefix}*"
            echo ""
            if (!params.skipCleanup) {
                echo "Resources cleaned up automatically"
            } else {
                echo "Cleanup skipped - manual cleanup required"
            }
            
        } catch (Exception e) {
            // Failure handling
            echo "FAILURE: Document Multiplication Test failed"
            echo ""
            echo "Debugging Information:"
            echo "  - Stage: ${params.stage}"
            echo "  - Region: ${params.region}"
            echo "  - Cluster Version: ${params.clusterVersion}"
            echo "  - Error: ${e.message}"
            echo ""
            echo "Resources preserved for debugging"
            echo "Manual cleanup commands:"
            echo "  cd test/cleanupDeployment"
            echo "  pipenv install --deploy --ignore-pipfile"
            echo "  pipenv run python3 cleanup_deployment.py --stage ${params.stage}"
            echo ""
            echo "Alternative manual cleanup (if needed):"
            if (env.SOURCE_CLUSTER_ENDPOINT && env.SOURCE_VPC_ID) {
                echo "  ./awsMigrationInfraSetup.sh --cleanup --source-endpoint ${env.SOURCE_CLUSTER_ENDPOINT} --source-version ${params.engineVersion} --vpc-id ${env.SOURCE_VPC_ID} --stage ${params.stage} --region ${params.region}"
            } else {
                echo "  ./awsMigrationInfraSetup.sh --cleanup --source-endpoint <ENDPOINT> --source-version ${params.engineVersion} --vpc-id <VPC_ID> --stage ${params.stage} --region ${params.region}"
            }
            echo "  ./awsSourceClusterSetup.sh --cleanup --cluster-version ${params.engineVersion} --stage ${params.stage} --region ${params.region}"
            
            throw e
        } finally {
            // Always execute
            echo ""
            echo "Pipeline Summary:"
            echo "  Result: ${currentBuild.result ?: 'SUCCESS'}"
            echo "  Stage: ${params.stage}"
            echo "  Region: ${params.region}"
            echo "  Debug Mode: ${params.debugMode ? 'Enabled' : 'Disabled'}"
            echo ""
        }
    }
}
