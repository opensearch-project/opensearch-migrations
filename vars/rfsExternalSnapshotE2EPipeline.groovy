// RFS External Snapshot E2E Pipeline Implementation
// Restructured to match the working reference pattern for reliability
// This pipeline deploys a target cluster, runs snapshot-based migration tests, and generates performance metrics

def call(Map config = [:]) {
    def params = config.params ?: [:]
    def workerAgent = config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host'
    
    // Parse migration context from the config
    def contexts = [:]
    if (config.migrationContext) {
        contexts.migration = readJSON text: config.migrationContext
    }
    
    node(workerAgent) {
        try {
            echo "Starting RFS External Snapshot E2E Pipeline with Performance Monitoring"
            echo "Target: Test migration from external snapshot to OpenSearch ${params.targetVersion}"
            echo "Stage: ${params.stage}"
            echo "Backfill Scale: ${params.backfillScale} workers"
            
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
            
            stage('2. Test Caller Identity') {
                echo "Stage 2: Verifying AWS credentials"
                
                sh 'aws sts get-caller-identity'
                
                echo "AWS credentials verified"
            }
            
            stage('3. Build') {
                timeout(time: 1, unit: 'HOURS') {
                    echo "Stage 3: Building project"
                    
                    sh './gradlew clean build --no-daemon --stacktrace'
                    
                    echo "Project built successfully"
                }
            }
            
            stage('4. Deploy Target Cluster') {
                timeout(time: 90, unit: 'MINUTES') {
                    echo "Stage 4: Deploying target cluster"
                    echo "Target Version: ${params.targetVersion}"
                    echo "Data Nodes: ${params.targetDataNodeCount} x ${params.targetDataNodeType}"
                    echo "Manager Nodes: ${params.targetManagerNodeCount} x ${params.targetManagerNodeType}"
                    echo "Region: ${params.region}"
                    
                    dir('test') {
                        // Use the target cluster setup script with Jenkins parameters
                        def command = "./awsTargetClusterSetup.sh " +
                            "--cluster-version ${params.targetVersion} " +
                            "--stage ${params.stage} " +
                            "--region ${params.region} " +
                            "--target-data-node-type ${params.targetDataNodeType} " +
                            "--target-data-node-count ${params.targetDataNodeCount} " +
                            "--target-manager-node-type ${params.targetManagerNodeType} " +
                            "--target-manager-node-count ${params.targetManagerNodeCount} " +
                            "--target-ebs-enabled ${params.targetEbsEnabled}"
                        
                        def clusterOutput = ""
                        withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                            withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", duration: 3600, roleSessionName: 'jenkins-session') {
                                clusterOutput = sh(script: command, returnStdout: true)
                            }
                        }
                        
                        // Parse cluster information from output and set environment variables
                        echo "Parsing target cluster information from Stage 4 output..."
                        def endpointMatch = clusterOutput =~ /CLUSTER_ENDPOINT=(.+)/
                        def vpcMatch = clusterOutput =~ /VPC_ID=(.+)/
                        def domainMatch = clusterOutput =~ /DOMAIN_NAME=(.+)/
                        
                        if (endpointMatch) {
                            env.TARGET_CLUSTER_ENDPOINT = endpointMatch[0][1].trim()
                            echo "Captured TARGET_CLUSTER_ENDPOINT: ${env.TARGET_CLUSTER_ENDPOINT}"
                        } else {
                            error("Failed to extract CLUSTER_ENDPOINT from Stage 4 output")
                        }
                        
                        if (vpcMatch) {
                            env.TARGET_VPC_ID = vpcMatch[0][1].trim()
                            echo "Captured TARGET_VPC_ID: ${env.TARGET_VPC_ID}"
                        } else {
                            error("Failed to extract VPC_ID from Stage 4 output")
                        }
                        
                        if (domainMatch) {
                            env.TARGET_DOMAIN_NAME = domainMatch[0][1].trim()
                            echo "Captured TARGET_DOMAIN_NAME: ${env.TARGET_DOMAIN_NAME}"
                        }
                        
                        // Validate captured values
                        if (!env.TARGET_CLUSTER_ENDPOINT || env.TARGET_CLUSTER_ENDPOINT.isEmpty()) {
                            error("Invalid TARGET_CLUSTER_ENDPOINT captured from Stage 4")
                        }
                        if (!env.TARGET_VPC_ID || env.TARGET_VPC_ID.isEmpty()) {
                            error("Invalid TARGET_VPC_ID captured from Stage 4")
                        }
                        
                        // Additional validation for proper format
                        if (!env.TARGET_CLUSTER_ENDPOINT.startsWith("https://")) {
                            error("Invalid TARGET_CLUSTER_ENDPOINT format: ${env.TARGET_CLUSTER_ENDPOINT}")
                        }
                        if (!env.TARGET_VPC_ID.startsWith("vpc-")) {
                            error("Invalid TARGET_VPC_ID format: ${env.TARGET_VPC_ID}")
                        }
                    }
                    
                    echo "Target cluster deployed successfully"
                    echo "Cluster information captured in environment variables"
                }
            }
            
            stage('5. Deploy Migration Assistant') {
                timeout(time: 90, unit: 'MINUTES') {
                    echo "Stage 5: Deploying migration infrastructure"
                    
                    dir('test') {
                        // Use cluster information captured from Stage 4 environment variables
                        def targetEndpoint = env.TARGET_CLUSTER_ENDPOINT
                        def vpcId = env.TARGET_VPC_ID
                        
                        echo "Using cluster information from Stage 4 environment variables:"
                        echo "Target Endpoint: ${targetEndpoint}"
                        echo "VPC ID: ${vpcId}"
                        echo "Source S3 URI: ${params.snapshotS3Uri}"
                        
                        // Validate that we have the required values from Stage 4
                        if (!targetEndpoint || targetEndpoint.isEmpty()) {
                            error("TARGET_CLUSTER_ENDPOINT not available from Stage 4. Check Stage 4 execution.")
                        }
                        if (!vpcId || vpcId.isEmpty()) {
                            error("TARGET_VPC_ID not available from Stage 4. Check Stage 4 execution.")
                        }
                        
                        // Additional validation for proper format
                        if (!targetEndpoint.startsWith("https://")) {
                            error("Invalid TARGET_CLUSTER_ENDPOINT format: ${targetEndpoint}")
                        }
                        if (!vpcId.startsWith("vpc-")) {
                            error("Invalid TARGET_VPC_ID format: ${vpcId}")
                        }
                        
                        // Write migration context to file with captured target cluster info
                        sh 'mkdir -p tmp'
                        writeJSON file: 'tmp/migrationContext.json', json: contexts.migration
                        
                        // Update migration context with target cluster information
                        sh "sed -i -e 's/<VPC_ID>/${vpcId}/g' tmp/migrationContext.json"
                        sh "sed -i -e 's|<TARGET_CLUSTER_ENDPOINT>|${targetEndpoint}|g' tmp/migrationContext.json"
                        sh "sed -i -e 's|<SNAPSHOT_S3_URI>|${params.snapshotS3Uri}|g' tmp/migrationContext.json"
                        
                        if (params.debugMode) {
                            sh "echo 'Updated Migration Context:' && cat tmp/migrationContext.json"
                        }
                        
                        // Deploy Migration Assistant infrastructure using focused script
                        def command = "./awsMigrationAssistantSetup.sh " +
                            "--target-endpoint ${targetEndpoint} " +
                            "--target-version ${params.targetVersion} " +
                            "--vpc-id ${vpcId} " +
                            "--snapshot-s3-uri ${params.snapshotS3Uri} " +
                            "--snapshot-name ${params.snapshotName} " +
                            "--snapshot-repo ${params.snapshotRepoName} " +
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
            
            stage('6. Enhanced Integration Test with Metrics Collection') {
                timeout(time: 120, unit: 'MINUTES') {
                    echo "Stage 6: Enhanced Integration Test with Metrics Collection"
                    echo "Running 4-phase external backfill test:"
                    echo "  Phase 1: Read Catalog File for Expected Metrics"
                    echo "  Phase 2: Metadata Migration"
                    echo "  Phase 3: RFS Backfill with Document Count Monitoring (${params.backfillScale} workers)"
                    echo "  Phase 4: Performance Metrics Calculation"
                    
                    dir('test') {
                        // Use standard pytest command following defaultIntegPipeline pattern
                        def testDir = "/root/lib/integ_test/integ_test"
                        def test_result_file = "${testDir}/reports/${params.testUniqueId}/report.xml"
                        def command = "bash -c \"source /.venv/bin/activate && " +
                            "export CONFIG_FILE_PATH='/config/migration_services.yaml' && " +
                            "export SNAPSHOT_S3_URI='${params.snapshotS3Uri}' && " +
                            "export STAGE='${params.stage}' && " +
                            "export BACKFILL_SCALE='${params.backfillScale}' && " +
                            "export UNIQUE_ID='${params.testUniqueId}' && " +
                            "cd /root/lib/integ_test && " +
                            "pipenv run pytest --log-file=${testDir}/reports/${params.testUniqueId}/pytest.log " +
                            "--junitxml=${test_result_file} integ_test/external_backfill_test.py " +
                            "--unique_id ${params.testUniqueId} " +
                            "--stage ${params.stage} " +
                            "-s\""
                        
                        withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                            withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", duration: 3600, roleSessionName: 'jenkins-session') {
                                sh "./awsRunIntegTests.sh --command '${command}' " +
                                   "--test-result-file ${test_result_file} " +
                                   "--stage ${params.stage}"
                            }
                        }
                    }
                    
                    echo "Enhanced Integration Test completed successfully"
                }
            }
            
            stage('7. Metrics Collection and Plotting') {
                timeout(time: 15, unit: 'MINUTES') {
                    echo "Stage 7: Metrics Collection and Plotting"
                    echo "Starting metrics collection for test: ${params.testUniqueId}"
                    
                    dir('test') {
                        // Create metrics output directory
                        sh "mkdir -p ${config.metricsOutputDir}"
                        
                        withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                            withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", duration: 3600, roleSessionName: 'jenkins-session') {
                                // Retrieve metrics file from ECS container
                                echo "Retrieving metrics file from container: ${config.remoteMetricsPath}"
                                def task_arn = sh(script: "aws ecs list-tasks --cluster migration-${params.stage}-ecs-cluster --family 'migration-${params.stage}-migration-console' | jq --raw-output '.taskArns[0]'", returnStdout: true).trim()
                                
                                sh """
                                    mkdir -p \$(dirname ${config.localMetricsPath})
                                    aws ecs execute-command --cluster "migration-${params.stage}-ecs-cluster" --task "${task_arn}" --container "migration-console" --interactive --command "cat ${config.remoteMetricsPath}" > ${config.localMetricsPath}
                                """
                            }
                        }
                        
                        // Archive metrics file
                        archiveArtifacts artifacts: "${config.metricsOutputDir}/*", allowEmptyArchive: true
                        
                        // Execute plotting callback
                        echo "Executing plotting callback..."
                        if (config.plotMetricsCallback) {
                            config.plotMetricsCallback()
                        } else {
                            echo "No plotting callback provided"
                        }
                    }
                    
                    echo "Metrics collection and plotting completed successfully"
                }
            }
            
            // Success-only cleanup stage
            if (!params.skipCleanup && (currentBuild.result == null || currentBuild.result == 'SUCCESS')) {
                stage('8. CDK-Based Cleanup') {
                    timeout(time: 1, unit: 'HOURS') {
                        echo "Stage 8: CDK-Based Cleanup - Using proper CDK destroy commands"
                        echo "Cleanup Mode: Success-only (preserves resources on failure for debugging)"
                        echo "This matches the official customer cleanup process"
                        
                        withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                            withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", duration: 3600, roleSessionName: 'jenkins-session') {
                                
                                // First: Clean up Migration Assistant infrastructure using full context
                                echo "Cleaning up Migration Assistant infrastructure using CDK destroy..."
                                dir('test') {
                                    sh """
                                        echo "Destroying Migration Assistant CDK stacks with context..."
                                        echo "This will destroy in proper dependency order:"
                                        echo "  - MigrationConsole"
                                        echo "  - ReindexFromSnapshot" 
                                        echo "  - MigrationInfra"
                                        echo "  - NetworkInfra"
                                        
                                        # Use the migration assistant script's cleanup function with full context
                                        # This generates context with actual values instead of placeholders
                                        ./awsMigrationAssistantSetup.sh --cleanup \\
                                            --target-endpoint "${env.TARGET_CLUSTER_ENDPOINT}" \\
                                            --target-version "${params.targetVersion}" \\
                                            --vpc-id "${env.TARGET_VPC_ID}" \\
                                            --snapshot-s3-uri "${params.snapshotS3Uri}" \\
                                            --snapshot-name "${params.snapshotName}" \\
                                            --snapshot-repo "${params.snapshotRepoName}" \\
                                            --stage "${params.stage}" \\
                                            --region "${params.region}"
                                        
                                        if [ \$? -eq 0 ]; then
                                            echo "Migration Assistant infrastructure cleaned up successfully"
                                        else
                                            echo "CDK destroy completed with warnings (some resources may have been already deleted)"
                                        fi
                                    """
                                }
                                
                                // Second: Clean up target cluster (AWS Samples CDK) - ACTUALLY EXECUTE CLEANUP
                                echo "Cleaning up target cluster infrastructure..."
                                dir('test') {
                                    sh """
                                        echo "Destroying target cluster CDK stacks..."
                                        echo "This will destroy:"
                                        echo "  - OpenSearchDomain-target-os2x-${params.stage}-${params.region}"
                                        echo "  - NetworkInfra-${params.stage}-${params.region}"
                                        echo ""
                                        
                                        # Execute target cluster cleanup with full context
                                        ./awsTargetClusterSetup.sh --cleanup \\
                                            --cluster-version ${params.targetVersion} \\
                                            --stage ${params.stage} \\
                                            --region ${params.region}
                                        
                                        if [ \$? -eq 0 ]; then
                                            echo "Target cluster infrastructure cleaned up successfully"
                                        else
                                            echo "Target cluster cleanup completed with warnings (some resources may have been already deleted)"
                                        fi
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
                        echo "Target cluster infrastructure destroyed - complete cleanup achieved"
                    }
                }
            }
            
            echo ""
            echo "SUCCESS: RFS External Snapshot E2E Test completed successfully!"
            echo ""
            echo "Test Results Summary:"
            echo "  Target Cluster: ${params.targetVersion}"
            echo "  Data Nodes: ${params.targetDataNodeCount} x ${params.targetDataNodeType}"
            echo "  Manager Nodes: ${params.targetManagerNodeCount} x ${params.targetManagerNodeType}"
            echo "  Snapshot Source: ${params.snapshotName} from ${params.snapshotRepoName}"
            echo "  RFS Workers: ${params.backfillScale}"
            echo "  Test ID: ${params.testUniqueId}"
            echo ""
            echo "Performance metrics collected successfully"
            echo ""
            
        } catch (Exception e) {
            echo ""
            echo "FAILURE: RFS External Snapshot E2E Test failed"
            echo ""
            echo "Debugging Information:"
            echo "  - Stage: ${params.stage}"
            echo "  - Target Version: ${params.targetVersion}"
            echo "  - Snapshot: ${params.snapshotName}"
            echo "  - Test ID: ${params.testUniqueId}"
            echo "  - Error: ${e.message}"
            echo ""
            echo "Resources preserved for debugging"
            echo ""
            echo "Manual Cleanup Commands:"
            echo "  # Clean up Migration Assistant infrastructure"
            echo "  cd test"
            echo "  ./awsMigrationAssistantSetup.sh --cleanup --stage ${params.stage}"
            echo ""
            echo "  # Clean up Target Cluster"
            echo "  ./awsTargetClusterSetup.sh --cleanup --cluster-version ${params.targetVersion} --stage ${params.stage}"
            echo ""
            
            throw e
            
        } finally {
            echo ""
            echo "Pipeline Summary:"
            echo "  Result: ${currentBuild.result ?: 'SUCCESS'}"
            echo "  Stage: ${params.stage}"
            echo "  Target Version: ${params.targetVersion}"
            echo "  Test ID: ${params.testUniqueId}"
            echo "  Backfill Scale: ${params.backfillScale} workers"
            echo ""
            echo "Note: Cleanup handled by Stage 8 (success-only) or manual commands (on failure)"
            echo "External snapshot remains untouched as requested"
        }
    }
}
