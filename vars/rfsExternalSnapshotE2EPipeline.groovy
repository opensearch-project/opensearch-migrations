// RFS External Snapshot E2E Pipeline Implementation
// This pipeline deploys a target cluster, runs snapshot-based migration tests, and generates performance metrics
// Following DocMultiplicationPipeline pattern for reliable agent context and cleanup

def call(Map config = [:]) {
    def sourceContext = config.sourceContext
    def migrationContext = config.migrationContext
    def sourceContextId = config.sourceContextId ?: 'source-empty'
    def migrationContextId = config.migrationContextId ?: 'migration-rfs-external-snapshot'
    def stageId = config.stageId ?: 'rfs-external-snapshot-integ'
    def lockResourceName = config.lockResourceName ?: stageId
    def testUniqueId = config.testUniqueId
    def remoteMetricsPath = config.remoteMetricsPath
    def localMetricsPath = config.localMetricsPath
    def metricsOutputDir = config.metricsOutputDir
    def plotMetricsCallback = config.plotMetricsCallback
    
    // Extract parameters from config (passed from cover file)
    def params = [
        GIT_REPO_URL: config.GIT_REPO_URL ?: 'https://github.com/jugal-chauhan/opensearch-migrations.git',
        GIT_BRANCH: config.GIT_BRANCH ?: 'jenkins-rfs-metrics',
        STAGE: config.STAGE ?: 'rfs-metrics',
        BACKFILL_SCALE: config.BACKFILL_SCALE ?: '80',
        CUSTOM_COMMIT: config.CUSTOM_COMMIT ?: '',
        SNAPSHOT_S3_URI: config.SNAPSHOT_S3_URI,
        SNAPSHOT_NAME: config.SNAPSHOT_NAME ?: 'large-snapshot',
        SNAPSHOT_REGION: config.SNAPSHOT_REGION ?: 'us-west-2',
        SNAPSHOT_REPO_NAME: config.SNAPSHOT_REPO_NAME ?: 'migration_assistant_repo',
        TARGET_VERSION: config.TARGET_VERSION ?: 'OS_2.19',
        TARGET_DATA_NODE_COUNT: config.TARGET_DATA_NODE_COUNT ?: '10',
        TARGET_DATA_NODE_TYPE: config.TARGET_DATA_NODE_TYPE ?: 'r6g.4xlarge.search',
        TARGET_MANAGER_NODE_COUNT: config.TARGET_MANAGER_NODE_COUNT ?: '4',
        TARGET_MANAGER_NODE_TYPE: config.TARGET_MANAGER_NODE_TYPE ?: 'm6g.xlarge.search',
        TARGET_EBS_ENABLED: config.TARGET_EBS_ENABLED ?: true
    ]
    
    // Debug parameter passing
    echo "DEBUG: config.SNAPSHOT_S3_URI = '${config.SNAPSHOT_S3_URI}'"
    echo "DEBUG: params.SNAPSHOT_S3_URI = '${params.SNAPSHOT_S3_URI}'"

    if(sourceContext == null || sourceContext.isEmpty()){
        throw new RuntimeException("The sourceContext argument must be provided");
    }
    if(migrationContext == null || migrationContext.isEmpty()){
        throw new RuntimeException("The migrationContext argument must be provided");
    }

    def source_context_file_name = 'sourceJenkinsContext.json'
    def migration_context_file_name = 'migrationJenkinsContext.json'
    def workerAgent = 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host'

    // Use single node pattern like DocMultiplicationPipeline for reliable agent context
    node(workerAgent) {
        try {
            echo "Starting RFS External Snapshot E2E Pipeline with Performance Monitoring"
            echo "Target: Test migration from external snapshot to OpenSearch ${params.TARGET_VERSION}"
            echo "Stage: ${params.STAGE}"
            echo "Backfill Scale: ${params.BACKFILL_SCALE} workers"
            
            // Set environment variables
            env.AWS_DEFAULT_REGION = params.SNAPSHOT_REGION
            env.STAGE = params.STAGE
            env.BACKFILL_SCALE = params.BACKFILL_SCALE
            
            stage('1. Enhanced Checkout & Setup') {
                timeout(time: 15, unit: 'MINUTES') {
                    echo "=== STAGE 1: ENHANCED CHECKOUT & SETUP ==="
                    
                    // Clean workspace
                    sh 'sudo chown -R $(whoami) . || true'
                    sh 'sudo chmod -R u+w . || true'
                    if (sh(script: 'git rev-parse --git-dir > /dev/null 2>&1', returnStatus: true) == 0) {
                        echo 'Cleaning any existing git files in workspace'
                        sh 'git reset --hard'
                        sh 'git clean -fd'
                    }
                    
                    // Checkout your fork for pipeline code
                    git branch: "${params.GIT_BRANCH}", url: "${params.GIT_REPO_URL}"
                    
                    // Determine commit to test from main repo
                    if (params.CUSTOM_COMMIT) {
                        env.TEST_COMMIT = params.CUSTOM_COMMIT
                        echo "Using custom commit: ${env.TEST_COMMIT}"
                    } else {
                        env.TEST_COMMIT = sh(
                            script: "git ls-remote https://github.com/opensearch-project/opensearch-migrations.git refs/heads/main | cut -f1",
                            returnStdout: true
                        ).trim()
                        echo "Using latest main commit: ${env.TEST_COMMIT}"
                    }
                    
                    // Get commit info for metrics
                    env.TEST_COMMIT_SHORT = env.TEST_COMMIT.take(7)
                    env.COMMIT_DATE = sh(
                        script: "git log -1 --format='%cd' --date=format:'%b %d' ${env.TEST_COMMIT} 2>/dev/null || echo 'Unknown'",
                        returnStdout: true
                    ).trim()
                    
                    // Use direct S3 URI parameter
                    if (params.SNAPSHOT_S3_URI) {
                        env.SNAPSHOT_S3_URI = params.SNAPSHOT_S3_URI
                        echo "Using direct S3 URI parameter: ${env.SNAPSHOT_S3_URI}"
                    } else {
                        error("SNAPSHOT_S3_URI parameter is required")
                    }
                    
                    // AWS identity verification
                    sh 'aws sts get-caller-identity'
                    
                    echo "Commit for metrics: ${env.TEST_COMMIT_SHORT} (${env.COMMIT_DATE})"
                    echo "Stage 1 completed successfully"
                }
            }

            stage('2. Setup Enhanced CDK Context') {
                timeout(time: 10, unit: 'MINUTES') {
                    echo "=== STAGE 2: SETUP ENHANCED CDK CONTEXT ==="
                    
                    // Generate enhanced context files with parameter substitution
                    def populatedSourceContext = sourceContext
                        .replaceAll('<STAGE>', params.STAGE)
                    
                    def populatedMigrationContext = migrationContext
                        .replaceAll('<STAGE>', params.STAGE)
                        .replaceAll('<TARGET_VERSION>', params.TARGET_VERSION)
                        .replaceAll('<TARGET_DATA_NODE_COUNT>', params.TARGET_DATA_NODE_COUNT)
                        .replaceAll('<TARGET_DATA_NODE_TYPE>', params.TARGET_DATA_NODE_TYPE)
                        .replaceAll('<TARGET_EBS_ENABLED>', params.TARGET_EBS_ENABLED.toString())
                        .replaceAll('<TARGET_MANAGER_NODE_COUNT>', params.TARGET_MANAGER_NODE_COUNT)
                        .replaceAll('<TARGET_MANAGER_NODE_TYPE>', params.TARGET_MANAGER_NODE_TYPE)
                        .replaceAll('<SNAPSHOT_S3_URI>', env.SNAPSHOT_S3_URI)
                        .replaceAll('<SNAPSHOT_NAME>', params.SNAPSHOT_NAME)
                        .replaceAll('<SNAPSHOT_REPO_NAME>', params.SNAPSHOT_REPO_NAME)
                        .replaceAll('<SNAPSHOT_REGION>', params.SNAPSHOT_REGION)
                    
                    writeFile(file: "test/${source_context_file_name}", text: populatedSourceContext)
                    writeFile(file: "test/${migration_context_file_name}", text: populatedMigrationContext)
                    
                    echo 'Using source context:'
                    sh "cat test/${source_context_file_name}"
                    echo 'Using migration context:'
                    sh "cat test/${migration_context_file_name}"
                    
                    echo "Stage 2 completed successfully"
                }
            }

            stage('3. Build') {
                timeout(time: 60, unit: 'MINUTES') {
                    echo "=== STAGE 3: BUILD ==="
                    
                    sh './gradlew clean build --no-daemon --stacktrace'
                    
                    echo "Stage 3 completed successfully"
                }
            }

            stage('4. Deploy Target Cluster') {
                timeout(time: 90, unit: 'MINUTES') {
                    dir('test') {
                        echo "=== STAGE 4: DEPLOY TARGET CLUSTER ==="
                        echo "Target Version: ${params.TARGET_VERSION}"
                        echo "Data Nodes: ${params.TARGET_DATA_NODE_COUNT} x ${params.TARGET_DATA_NODE_TYPE}"
                        echo "Manager Nodes: ${params.TARGET_MANAGER_NODE_COUNT} x ${params.TARGET_MANAGER_NODE_TYPE}"
                        echo "EBS Enabled: ${params.TARGET_EBS_ENABLED}"
                        
                        // Generate target cluster context JSON file for AWS Samples CDK
                        // Based on the AWS Samples CDK structure and test examples
                        def clusterId = "target-os2x"
                        
                        def targetClusterContext = """
                        {
                          "stage": "${params.STAGE}",
                          "clusters": [
                            {
                              "clusterId": "${clusterId}",
                              "clusterName": "target-os2x-rfs-metrics",
                              "clusterType": "OPENSEARCH_MANAGED_SERVICE",
                              "clusterVersion": "${params.TARGET_VERSION}",
                              "dataNodeType": "${params.TARGET_DATA_NODE_TYPE}",
                              "dataNodeCount": ${params.TARGET_DATA_NODE_COUNT},
                              "dedicatedManagerNodeType": "${params.TARGET_MANAGER_NODE_TYPE}",
                              "dedicatedManagerNodeCount": ${params.TARGET_MANAGER_NODE_COUNT},
                              "ebsEnabled": ${params.TARGET_EBS_ENABLED},
                              "ebsVolumeType": "GP3",
                              "ebsVolumeSize": 100,
                              "enforceHTTPS": true,
                              "tlsSecurityPolicy": "TLS_1_2",
                              "encryptionAtRestEnabled": true,
                              "nodeToNodeEncryptionEnabled": true,
                              "openAccessPolicyEnabled": true,
                              "domainRemovalPolicy": "DESTROY"
                            }
                          ]
                        }
                        """
                        
                        writeFile(file: "targetClusterContext.json", text: targetClusterContext)
                        
                        echo 'Generated target cluster context:'
                        sh "cat targetClusterContext.json"
                        
                        withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                            withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", duration: 5400, roleSessionName: 'jenkins-session') {
                                // Deploy target cluster using enhanced setup script with context file
                                sh """
                                    ./awsEnhancedE2ESolutionSetup.sh \\
                                        --deploy-target-only \\
                                        --stage ${params.STAGE} \\
                                        --target-context-file ./targetClusterContext.json \\
                                        --target-context-id ${clusterId}
                                """
                            }
                        }
                        
                        // Extract target cluster information using AWS Samples CDK stack names
                        // Network Stack: NetworkInfra-{stage}-{region}
                        // Domain Stack: OpenSearchDomain-{clusterId}-{stage}-{region}
                        def networkStackName = "NetworkInfra-${params.STAGE}-${params.SNAPSHOT_REGION}"
                        def domainStackName = "OpenSearchDomain-${clusterId}-${params.STAGE}-${params.SNAPSHOT_REGION}"
                        
                        echo "Looking for stacks: ${networkStackName}, ${domainStackName}"
                        
                        // Get VPC ID from CloudFormation export (AWS Samples CDK creates exports)
                        // Using the same pattern as source cluster extraction
                        env.TARGET_VPC_ID = sh(
                            script: "aws cloudformation list-exports --region ${params.SNAPSHOT_REGION} --query \"Exports[?Name=='VpcId-${params.STAGE}'].Value\" --output text 2>/dev/null || echo ''",
                            returnStdout: true
                        ).trim()
                        
                        // Get cluster endpoint from CloudFormation export
                        env.TARGET_ENDPOINT = sh(
                            script: "aws cloudformation list-exports --region ${params.SNAPSHOT_REGION} --query \"Exports[?Name=='ClusterEndpoint-${params.STAGE}-${clusterId}'].Value\" --output text 2>/dev/null || echo ''",
                            returnStdout: true
                        ).trim()
                        
                        // Fallback to stack outputs if exports don't work
                        if (!env.TARGET_VPC_ID) {
                            env.TARGET_VPC_ID = sh(
                                script: "aws cloudformation describe-stacks --stack-name '${networkStackName}' --region ${params.SNAPSHOT_REGION} --query 'Stacks[0].Outputs[?contains(OutputValue, \"vpc\")].OutputValue' --output text 2>/dev/null || echo ''",
                                returnStdout: true
                            ).trim()
                        }
                        
                        if (!env.TARGET_ENDPOINT) {
                            env.TARGET_ENDPOINT = sh(
                                script: "aws cloudformation describe-stacks --stack-name '${domainStackName}' --region ${params.SNAPSHOT_REGION} --query 'Stacks[0].Outputs[?OutputKey==\\`domainEndpoint\\`].OutputValue' --output text 2>/dev/null || echo ''",
                                returnStdout: true
                            ).trim()
                        }
                        
                        echo "Target VPC ID: ${env.TARGET_VPC_ID}"
                        echo "Target Endpoint: ${env.TARGET_ENDPOINT}"
                        
                        if (!env.TARGET_VPC_ID || !env.TARGET_ENDPOINT) {
                            error("Failed to extract target cluster information")
                        }
                        
                        echo "Stage 4 completed successfully"
                    }
                }
            }

            stage('5. Deploy Migration Assistant') {
                timeout(time: 90, unit: 'MINUTES') {
                    dir('test') {
                        echo "=== STAGE 5: DEPLOY MIGRATION ASSISTANT ==="
                        echo "Configuring MA for snapshot-based migration"
                        echo "Target VPC: ${env.TARGET_VPC_ID}"
                        echo "Target Endpoint: ${env.TARGET_ENDPOINT}"
                        
                        // Update migration context with target cluster information
                        sh "sed -i -e 's/<VPC_ID>/${env.TARGET_VPC_ID}/g' ${migration_context_file_name}"
                        
                        echo 'Updated migration context:'
                        sh "cat ${migration_context_file_name}"
                        
                        withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                            withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", duration: 5400, roleSessionName: 'jenkins-session') {
                                // Deploy Migration Assistant infrastructure
                                sh """
                                    ./awsEnhancedE2ESolutionSetup.sh \\
                                        --deploy-migration-only \\
                                        --migration-context-file './${migration_context_file_name}' \\
                                        --migration-context-id ${migrationContextId} \\
                                        --stage ${params.STAGE} \\
                                        --skip-capture-proxy \\
                                        --migrations-git-url ${params.GIT_REPO_URL} \\
                                        --migrations-git-branch ${params.GIT_BRANCH}
                                """
                            }
                        }
                        
                        echo "Stage 5 completed successfully"
                    }
                }
            }

            stage('6. Enhanced Integration Test with Metrics Collection') {
                timeout(time: 120, unit: 'MINUTES') {
                    dir('test') {
                        echo "=== STAGE 6: ENHANCED INTEGRATION TEST WITH METRICS COLLECTION ==="
                        echo "Running 3-phase external backfill test:"
                        echo "  Phase 1: Metadata Migration"
                        echo "  Phase 2: RFS Backfill (${params.BACKFILL_SCALE} workers)"
                        echo "  Phase 3: Performance Metrics Calculation"
                        
                        // Enhanced test command with all parameters
                        def testCommand = "python /root/lib/integ_test/integ_test/external_backfill_test.py " +
                                        "--config-file-path /config/migration_services.yaml " +
                                        "--stage ${params.STAGE} " +
                                        "--snapshot-name ${params.SNAPSHOT_NAME} " +
                                        "--snapshot-repo ${params.SNAPSHOT_REPO_NAME} " +
                                        "--backfill-scale ${params.BACKFILL_SCALE} " +
                                        "--unique-id ${testUniqueId}"
                        
                        withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                            withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", duration: 3600, roleSessionName: 'jenkins-session') {
                                // Execute test with metrics collection
                                sh "./awsRunIntegTests.sh --command '${testCommand}' --stage ${params.STAGE}"
                            }
                        }
                        
                        echo "Stage 6 completed successfully"
                    }
                }
            }

            stage('7. Metrics Collection and Plotting') {
                timeout(time: 15, unit: 'MINUTES') {
                    dir('test') {
                        echo "=== STAGE 7: METRICS COLLECTION AND PLOTTING ==="
                        echo "Starting metrics collection for commit: ${env.TEST_COMMIT_SHORT} (${env.COMMIT_DATE})"
                        
                        // Create metrics output directory
                        sh "mkdir -p ${metricsOutputDir}"
                        
                        withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                            withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", duration: 3600, roleSessionName: 'jenkins-session') {
                                // Retrieve metrics file from ECS container
                                sh "./awsRunIntegTests.sh --retrieve-file ${remoteMetricsPath} --local-path ${localMetricsPath} --stage ${params.STAGE}"
                            }
                        }
                        
                        // Execute plotting callback
                        plotMetricsCallback()
                        
                        // Archive metrics file
                        archiveArtifacts artifacts: "${metricsOutputDir}/*", allowEmptyArchive: true
                        
                        echo "Stage 7 completed successfully"
                    }
                }
            }
            
            echo ""
            echo "SUCCESS: RFS External Snapshot E2E Test completed successfully!"
            echo ""
            echo "Test Results Summary:"
            echo "  Target Cluster: ${params.TARGET_VERSION}"
            echo "  Data Nodes: ${params.TARGET_DATA_NODE_COUNT} x ${params.TARGET_DATA_NODE_TYPE}"
            echo "  Manager Nodes: ${params.TARGET_MANAGER_NODE_COUNT} x ${params.TARGET_MANAGER_NODE_TYPE}"
            echo "  Snapshot Source: ${params.SNAPSHOT_NAME} from ${params.SNAPSHOT_REPO_NAME}"
            echo "  RFS Workers: ${params.BACKFILL_SCALE}"
            echo "  Commit Tested: ${env.TEST_COMMIT_SHORT} (${env.COMMIT_DATE})"
            echo ""
            echo "Performance metrics collected and plotted successfully"
            echo ""
            
        } catch (Exception e) {
            echo ""
            echo "FAILURE: RFS External Snapshot E2E Test failed"
            echo ""
            echo "Debugging Information:"
            echo "  - Stage: ${params.STAGE}"
            echo "  - Target Version: ${params.TARGET_VERSION}"
            echo "  - Snapshot: ${params.SNAPSHOT_NAME}"
            echo "  - Commit: ${env.TEST_COMMIT_SHORT ?: 'Unknown'}"
            echo "  - Error: ${e.message}"
            echo ""
            echo "Resources preserved for debugging"
            echo ""
            echo "Manual Cleanup Commands:"
            echo "  # Clean up Migration Assistant infrastructure"
            echo "  cd test"
            echo "  ./awsEnhancedE2ESolutionSetup.sh --cleanup-migration --stage ${params.STAGE}"
            echo ""
            echo "  # Clean up Target Cluster"
            echo "  ./awsEnhancedE2ESolutionSetup.sh --cleanup-target --stage ${params.STAGE}"
            echo ""
            
            throw e
            
        } finally {
            timeout(time: 30, unit: 'MINUTES') {
                dir('test') {
                    echo ""
                    echo "=== CLEANUP PHASE ==="
                    echo "Starting dual cleanup process..."
                    echo "This cleanup runs regardless of test success/failure"
                    
                    try {
                        withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                            withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", duration: 3600, roleSessionName: 'jenkins-session') {
                                
                                // Cleanup Migration Assistant infrastructure
                                echo "Cleaning up Migration Assistant infrastructure..."
                                sh """
                                    ./awsEnhancedE2ESolutionSetup.sh --cleanup-migration --stage ${params.STAGE} || {
                                        echo "Migration cleanup completed with warnings (some resources may have been already deleted)"
                                        exit 0
                                    }
                                """
                                
                                // Cleanup Target Cluster
                                echo "Cleaning up Target Cluster..."
                                sh """
                                    ./awsEnhancedE2ESolutionSetup.sh --cleanup-target --stage ${params.STAGE} || {
                                        echo "Target cleanup completed with warnings (some resources may have been already deleted)"
                                        exit 0
                                    }
                                """
                                
                                echo "Note: External snapshot remains untouched as requested"
                            }
                        }
                        
                        echo "Dual cleanup process completed successfully"
                        
                    } catch (Exception cleanupError) {
                        echo "Cleanup encountered error: ${cleanupError.message}"
                        echo "Some resources may need manual cleanup"
                    }
                }
            }
            echo ""
            echo "Pipeline Summary:"
            echo "  Result: ${currentBuild.result ?: 'SUCCESS'}"
            echo "  Stage: ${params.STAGE}"
            echo "  Target Version: ${params.TARGET_VERSION}"
            echo "  Commit Tested: ${env.TEST_COMMIT_SHORT ?: 'Unknown'} (${env.COMMIT_DATE ?: 'Unknown'})"
            echo "  Backfill Scale: ${params.BACKFILL_SCALE} workers"
            echo ""
        }
    }
}
