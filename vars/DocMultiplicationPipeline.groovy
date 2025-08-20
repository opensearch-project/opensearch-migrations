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
                            "export TOTAL_DOCUMENTS_TO_INGEST='${params.docsToIngest}' && " +
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
                    echo "Docs Ingested: ${params.docsToIngest}"
                    echo "Multiplication Factor: ${params.multiplicationFactor}"
                    echo "RFS Workers: ${params.rfsWorkers}"
                    
                    dir('test') {
                        // Execute MultiplyDocuments module with bash -c wrapper for proper shell context
                        def command = "bash -c \"source /.venv/bin/activate && " +
                            "export INDEX_NAME='${params.indexName}' && " +
                            "export NUM_SHARDS='${params.numShards}' && " +
                            "export TOTAL_DOCUMENTS_TO_INGEST='${params.docsToIngest}' && " +
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
                            "export TOTAL_DOCUMENTS_TO_INGEST='${params.docsToIngest}' && " +
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
            
            // Stage 9: CDK-Based Cleanup
            if (!params.skipCleanup && (currentBuild.result == null || currentBuild.result == 'SUCCESS')) {
                stage('9. CDK-Based Cleanup') {
                    timeout(time: 1, unit: 'HOURS') {
                        echo "Stage 9: CDK-Based Cleanup - Using proper CDK destroy commands"
                        echo "Cleanup Mode: Success-only (preserves resources on failure for debugging)"
                        echo "This matches the official customer cleanup process"
                        
                        withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                            withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", duration: 3600, roleSessionName: 'jenkins-session') {
                                
                                // First: Clean up Migration Assistant infrastructure using CDK destroy
                                echo "Cleaning up Migration Assistant infrastructure using CDK destroy..."
                                dir('deployment/cdk/opensearch-service-migration') {
                                    sh """
                                        echo "Destroying Migration Assistant CDK stacks..."
                                        echo "This will destroy in proper dependency order:"
                                        echo "  - MigrationConsole"
                                        echo "  - ReindexFromSnapshot" 
                                        echo "  - MigrationInfra"
                                        echo "  - NetworkInfra"
                                        
                                        # Use CDK destroy with context (matches customer workflow)
                                        cdk destroy "*" --c contextId=default --force --verbose
                                        
                                        if [ \$? -eq 0 ]; then
                                            echo "Migration Assistant infrastructure cleaned up successfully"
                                        else
                                            echo "CDK destroy completed with warnings (some resources may have been already deleted)"
                                        fi
                                    """
                                }
                                
                                // Second: Clean up source cluster (AWS Samples CDK) - Optional based on reuse strategy
                                echo "Checking source cluster cleanup strategy..."
                                dir('test') {
                                    sh """
                                        echo "Source cluster reuse strategy: Keep source cluster for faster re-runs"
                                        echo "Source cluster stacks will be preserved:"
                                        echo "  - OpenSearchDomain-${params.clusterVersion}-${params.stage}-${params.region}"
                                        echo "  - NetworkInfra-${params.stage}-${params.region}"
                                        echo ""
                                        echo "To manually clean up source cluster later (saves 20+ minutes on next run):"
                                        echo "  cd test/tmp/amazon-opensearch-service-sample-cdk"
                                        echo "  cdk destroy '*' --force"
                                        echo ""
                                        echo "Or use the cleanup script:"
                                        echo "  ./awsSourceClusterSetup.sh --cleanup --cluster-version ${params.engineVersion} --stage ${params.stage} --region ${params.region}"
                                    """
                                }
                                
                                // Third: Clean up any orphaned bootstrap stacks if they exist
                                echo "Checking for orphaned bootstrap stacks..."
                                sh """
                                    echo "Checking for MigrationBootstrap stack..."
                                    if aws cloudformation describe-stacks --stack-name "MigrationBootstrap" --region ${params.region} >/dev/null 2>&1; then
                                        echo "Found MigrationBootstrap stack, cleaning up..."
                                        aws cloudformation delete-stack --stack-name "MigrationBootstrap" --region ${params.region}
                                        echo "MigrationBootstrap stack deletion initiated"
                                    else
                                        echo "No MigrationBootstrap stack found"
                                    fi
                                """
                            }
                        }
                        
                        echo "CDK-based cleanup completed successfully"
                        echo "Migration Assistant infrastructure destroyed using proper CDK commands"
                        echo "Source cluster preserved for reuse (saves 20+ minutes on next run)"
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
            echo ""
            echo "CDK-Based Manual Cleanup Commands (Recommended):"
            echo "  # Clean up Migration Assistant infrastructure"
            echo "  cd deployment/cdk/opensearch-service-migration"
            echo "  cdk destroy '*' --c contextId=default --force"
            echo ""
            echo "  # Clean up source cluster (optional - saves 20+ minutes on next run if kept)"
            echo "  cd test/tmp/amazon-opensearch-service-sample-cdk"
            echo "  cdk destroy '*' --force"
            echo ""
            echo "  # Or use source cluster cleanup script"
            echo "  ./awsSourceClusterSetup.sh --cleanup --cluster-version ${params.engineVersion} --stage ${params.stage} --region ${params.region}"
            echo ""
            echo "Legacy Cleanup (Fallback):"
            echo "  cd test/cleanupDeployment"
            echo "  pipenv install --deploy --ignore-pipfile"
            echo "  pipenv run python3 cleanup_deployment.py --stage ${params.stage}"
            
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
