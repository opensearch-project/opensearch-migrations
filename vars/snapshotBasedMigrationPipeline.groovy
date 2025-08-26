// Enhanced Snapshot-Based Migration Pipeline with Performance Monitoring
// This pipeline deploys a target OpenSearch 2.19 cluster, configures Migration Assistant for snapshot-based migration,
// runs comprehensive migration tests with performance metrics collection, and generates automated performance plots.

def call(Map config = [:]) {
    def sourceContextId = 'target-os219-context'
    def migrationContextId = 'snapshot-migration-context'
    def defaultStageId = config.defaultStageId ?: 'external-snapshot'
    def jobName = config.jobName ?: 'snapshot-based-migration-pipeline'
    
    // Enhanced source context for target OS 2.19 cluster deployment
    def target_cluster_context = """
        {
          "target-os219-context": {
            "suffix": "target-os219-<STAGE>",
            "networkStackSuffix": "target-os219-<STAGE>",
            "engineVersion": "OS_2.19",
            "domainName": "target-os219-<STAGE>",
            "dataNodeCount": "${TARGET_DATA_NODE_COUNT}",
            "dataNodeType": "${TARGET_DATA_NODE_TYPE}",
            "ebsEnabled": ${TARGET_EBS_ENABLED},
            "dedicatedManagerNodeCount": "${TARGET_MANAGER_NODE_COUNT}",
            "dedicatedManagerNodeType": "${TARGET_MANAGER_NODE_TYPE}",
            "domainAZCount": 2,
            "vpcEnabled": true,
            "vpcAZCount": 2,
            "openAccessPolicyEnabled": true,
            "domainRemovalPolicy": "DESTROY",
            "artifactBucketRemovalPolicy": "DESTROY",
            "tlsSecurityPolicy": "TLS_1_2",
            "enforceHTTPS": true,
            "nodeToNodeEncryptionEnabled": true,
            "encryptionAtRestEnabled": true,
            "captureProxyEnabled": false,
            "securityDisabled": false,
            "minDistribution": false,
            "cpuArch": "x64",
            "isInternal": false,
            "networkAvailabilityZones": 2,
            "serverAccessType": "ipv4",
            "restrictServerAccessTo": "0.0.0.0/0"
          }
        }
    """
    
    // Enhanced migration context for snapshot-based migration
    def snapshot_migration_context = """
        {
          "snapshot-migration-context": {
            "stage": "<STAGE>",
            "vpcId": "<VPC_ID>",
            "engineVersion": "OS_2.19",
            "domainName": "os-cluster-<STAGE>",
            "dataNodeCount": "${TARGET_DATA_NODE_COUNT}",
            "dataNodeType": "${TARGET_DATA_NODE_TYPE}",
            "ebsEnabled": ${TARGET_EBS_ENABLED},
            "dedicatedManagerNodeCount": "${TARGET_MANAGER_NODE_COUNT}",
            "dedicatedManagerNodeType": "${TARGET_MANAGER_NODE_TYPE}",
            "domainAZCount": 2,
            "reindexFromSnapshotServiceEnabled": true,
            "vpcEnabled": true,
            "vpcAZCount": 2,
            "migrationAssistanceEnabled": true,
            "migrationConsoleServiceEnabled": true,
            "otelCollectorEnabled": true,
            "openAccessPolicyEnabled": true,
            "domainRemovalPolicy": "DESTROY",
            "artifactBucketRemovalPolicy": "DESTROY",
            "tlsSecurityPolicy": "TLS_1_2",
            "enforceHTTPS": true,
            "nodeToNodeEncryptionEnabled": true,
            "encryptionAtRestEnabled": true,
            "trafficReplayerServiceEnabled": false,
            "captureProxyServiceEnabled": false,
            "targetClusterProxyServiceEnabled": false,
            "captureProxyDesiredCount": 0,
            "targetClusterProxyDesiredCount": 0,
            "replayerOutputEFSRemovalPolicy": "DESTROY",
            "sourceClusterEndpoint": "<TARGET_CLUSTER_ENDPOINT>",
            "snapshot": {
              "snapshotName": "${SNAPSHOT_NAME}",
              "snapshotRepoName": "${SNAPSHOT_REPO_NAME}",
              "s3": {
                "repo_uri": "${EXTERNAL_SNAPSHOT_S3_URI}",
                "aws_region": "${SNAPSHOT_REGION}"
              }
            }
          }
        }
    """

    pipeline {
        agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }

        parameters {
            // Git and deployment parameters
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/opensearch-project/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Git branch to use for repository')
            string(name: 'STAGE', defaultValue: "${defaultStageId}", description: 'Stage name for deployment environment')
            string(name: 'CUSTOM_COMMIT', defaultValue: '', description: 'Custom commit hash (leave empty for latest main)')
            
            // Target cluster configuration
            string(name: 'TARGET_DATA_NODE_COUNT', defaultValue: '10', description: 'Number of data nodes for target cluster')
            string(name: 'TARGET_DATA_NODE_TYPE', defaultValue: 'r7gd.8xlarge.search', description: 'Instance type for data nodes')
            booleanParam(name: 'TARGET_EBS_ENABLED', defaultValue: false, description: 'Enable EBS storage for target cluster')
            string(name: 'TARGET_MANAGER_NODE_COUNT', defaultValue: '3', description: 'Number of dedicated manager nodes')
            string(name: 'TARGET_MANAGER_NODE_TYPE', defaultValue: 'r7g.xlarge.search', description: 'Instance type for manager nodes')
            
            // Performance monitoring configuration
            string(name: 'BACKFILL_SCALE', defaultValue: '80', description: 'Number of RFS workers for backfill operation')
            
            // External snapshot configuration
            string(name: 'EXTERNAL_SNAPSHOT_S3_URI', defaultValue: '', description: 'S3 URI for external snapshot repository')
            string(name: 'SNAPSHOT_NAME', defaultValue: 'large-snapshot', description: 'Name of the snapshot to migrate from')
            string(name: 'SNAPSHOT_REPO_NAME', defaultValue: 'migration_assistant_repo', description: 'Snapshot repository name')
            string(name: 'SNAPSHOT_REGION', defaultValue: 'us-west-2', description: 'AWS region for snapshot')
        }

        options {
            // Acquire lock on a given deployment stage
            lock(label: params.STAGE, quantity: 1, variable: 'stage')
            timeout(time: 4, unit: 'HOURS')
            buildDiscarder(logRotator(daysToKeepStr: '30'))
            skipDefaultCheckout(true)
        }

        triggers {
            // Run every hour to monitor latest commits
            cron('H * * * *')
            
            // Existing webhook trigger for manual builds
            GenericTrigger(
                genericVariables: [
                    [key: 'GIT_REPO_URL', value: '$.GIT_REPO_URL'],
                    [key: 'GIT_BRANCH', value: '$.GIT_BRANCH'],
                    [key: 'CUSTOM_COMMIT', value: '$.CUSTOM_COMMIT']
                ],
                tokenCredentialId: 'jenkins-migrations-generic-webhook-token',
                causeString: 'Triggered by performance monitoring request',
                regexpFilterExpression: "^$jobName\$",
                regexpFilterText: "\$job_name"
            )
        }

        stages {
            stage('Enhanced Checkout & Setup') {
                steps {
                    timeout(time: 15, unit: 'MINUTES') {
                        script {
                            // Determine commit to use
                            def targetCommit = params.CUSTOM_COMMIT ?: 'main'
                            echo "Target commit: ${targetCommit}"
                            
                            // Clean workspace
                            sh 'sudo chown -R $(whoami) .'
                            sh 'sudo chmod -R u+w .'
                            if (sh(script: 'git rev-parse --git-dir > /dev/null 2>&1', returnStatus: true) == 0) {
                                echo 'Cleaning any existing git files in workspace'
                                sh 'git reset --hard'
                                sh 'git clean -fd'
                            }
                            
                            // Git checkout with commit tracking
                            if (params.CUSTOM_COMMIT) {
                                git branch: 'main', url: "${params.GIT_REPO_URL}"
                                sh "git checkout ${params.CUSTOM_COMMIT}"
                            } else {
                                git branch: "${params.GIT_BRANCH}", url: "${params.GIT_REPO_URL}"
                            }
                            
                            // Get commit info for metrics
                            env.CURRENT_COMMIT = sh(script: "git rev-parse --short HEAD", returnStdout: true).trim()
                            env.COMMIT_DATE = sh(script: "git log -1 --format='%cd' --date=format:'%b %d'", returnStdout: true).trim()
                            
                            // AWS identity verification
                            sh 'aws sts get-caller-identity'
                            
                            // Parameter validation
                            if (!params.EXTERNAL_SNAPSHOT_S3_URI) {
                                error("EXTERNAL_SNAPSHOT_S3_URI parameter is required")
                            }
                            
                            echo "Commit for metrics: ${env.CURRENT_COMMIT} (${env.COMMIT_DATE})"
                        }
                    }
                }
            }

            stage('Setup Enhanced CDK Context') {
                steps {
                    timeout(time: 10, unit: 'MINUTES') {
                        script {
                            // Generate enhanced context files with parameter substitution
                            def populatedTargetContext = target_cluster_context
                                .replaceAll('<STAGE>', params.STAGE)
                                .replaceAll('\\$\\{TARGET_DATA_NODE_COUNT\\}', params.TARGET_DATA_NODE_COUNT)
                                .replaceAll('\\$\\{TARGET_DATA_NODE_TYPE\\}', params.TARGET_DATA_NODE_TYPE)
                                .replaceAll('\\$\\{TARGET_EBS_ENABLED\\}', params.TARGET_EBS_ENABLED.toString())
                                .replaceAll('\\$\\{TARGET_MANAGER_NODE_COUNT\\}', params.TARGET_MANAGER_NODE_COUNT)
                                .replaceAll('\\$\\{TARGET_MANAGER_NODE_TYPE\\}', params.TARGET_MANAGER_NODE_TYPE)
                            
                            def populatedMigrationContext = snapshot_migration_context
                                .replaceAll('<STAGE>', params.STAGE)
                                .replaceAll('\\$\\{TARGET_DATA_NODE_COUNT\\}', params.TARGET_DATA_NODE_COUNT)
                                .replaceAll('\\$\\{TARGET_DATA_NODE_TYPE\\}', params.TARGET_DATA_NODE_TYPE)
                                .replaceAll('\\$\\{TARGET_EBS_ENABLED\\}', params.TARGET_EBS_ENABLED.toString())
                                .replaceAll('\\$\\{TARGET_MANAGER_NODE_COUNT\\}', params.TARGET_MANAGER_NODE_COUNT)
                                .replaceAll('\\$\\{TARGET_MANAGER_NODE_TYPE\\}', params.TARGET_MANAGER_NODE_TYPE)
                                .replaceAll('\\$\\{EXTERNAL_SNAPSHOT_S3_URI\\}', params.EXTERNAL_SNAPSHOT_S3_URI)
                                .replaceAll('\\$\\{SNAPSHOT_NAME\\}', params.SNAPSHOT_NAME)
                                .replaceAll('\\$\\{SNAPSHOT_REPO_NAME\\}', params.SNAPSHOT_REPO_NAME)
                                .replaceAll('\\$\\{SNAPSHOT_REGION\\}', params.SNAPSHOT_REGION)
                            
                            writeFile(file: "test/targetClusterContext.json", text: populatedTargetContext)
                            writeFile(file: "test/snapshotMigrationContext.json", text: populatedMigrationContext)
                            
                            echo 'Using target cluster context:'
                            sh "cat test/targetClusterContext.json"
                            echo 'Using snapshot migration context:'
                            sh "cat test/snapshotMigrationContext.json"
                        }
                    }
                }
            }

            stage('Build') {
                steps {
                    timeout(time: 60, unit: 'MINUTES') {
                        sh './gradlew clean build --no-daemon --stacktrace'
                    }
                }
            }

            stage('Deploy Target Cluster') {
                steps {
                    timeout(time: 90, unit: 'MINUTES') {
                        dir('test') {
                            script {
                                echo "Acquired deployment stage: ${stage}"
                                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", duration: 5400, roleSessionName: 'jenkins-session') {
                                        // Deploy target OS 2.19 cluster using enhanced setup script
                                        sh "./awsEnhancedE2ESolutionSetup.sh --target-context-file './targetClusterContext.json' " +
                                           "--target-context-id $sourceContextId " +
                                           "--stage ${stage} " +
                                           "--deploy-target-only " +
                                           "--migrations-git-url ${params.GIT_REPO_URL} " +
                                           "--migrations-git-branch ${params.GIT_BRANCH}"
                                    }
                                }
                                
                                // Extract target cluster information
                                env.TARGET_VPC_ID = sh(script: "aws cloudformation describe-stacks --stack-name 'opensearch-network-stack-target-os219-${stage}' --query 'Stacks[0].Outputs[?contains(OutputValue, \"vpc\")].OutputValue' --output text", returnStdout: true).trim()
                                env.TARGET_ENDPOINT = sh(script: "aws cloudformation describe-stacks --stack-name 'opensearch-infra-stack-target-os219-${stage}' --query 'Stacks[0].Outputs[?OutputKey==\\`domainEndpoint\\`].OutputValue' --output text", returnStdout: true).trim()
                                
                                echo "Target VPC ID: ${env.TARGET_VPC_ID}"
                                echo "Target Endpoint: ${env.TARGET_ENDPOINT}"
                            }
                        }
                    }
                }
            }

            stage('Deploy Migration Assistant') {
                steps {
                    timeout(time: 90, unit: 'MINUTES') {
                        dir('test') {
                            script {
                                // Update migration context with target cluster information
                                sh "sed -i -e 's/<VPC_ID>/${env.TARGET_VPC_ID}/g' snapshotMigrationContext.json"
                                sh "sed -i -e 's|<TARGET_CLUSTER_ENDPOINT>|https://${env.TARGET_ENDPOINT}:443|g' snapshotMigrationContext.json"
                                
                                echo 'Updated snapshot migration context:'
                                sh "cat snapshotMigrationContext.json"
                                
                                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", duration: 5400, roleSessionName: 'jenkins-session') {
                                        // Deploy Migration Assistant infrastructure
                                        sh "./awsEnhancedE2ESolutionSetup.sh --migration-context-file './snapshotMigrationContext.json' " +
                                           "--migration-context-id $migrationContextId " +
                                           "--stage ${stage} " +
                                           "--deploy-migration-only " +
                                           "--skip-capture-proxy " +
                                           "--migrations-git-url ${params.GIT_REPO_URL} " +
                                           "--migrations-git-branch ${params.GIT_BRANCH}"
                                    }
                                }
                            }
                        }
                    }
                }
            }

            stage('Enhanced Integration Test with Metrics Collection') {
                steps {
                    timeout(time: 120, unit: 'MINUTES') {
                        dir('test') {
                            script {
                                // Define metrics paths and test configuration
                                def testUniqueId = "backfill_${env.BUILD_NUMBER}_${System.currentTimeMillis()}"
                                def remoteMetricsPath = "/root/lib/integ_test/integ_test/reports/${testUniqueId}/backfill_metrics.csv"
                                def localMetricsPath = "backfill_metrics_${env.BUILD_NUMBER}.csv"
                                
                                // Enhanced test command with all parameters
                                def testCommand = "python /root/lib/integ_test/integ_test/external_backfill_test.py " +
                                                "--config-file-path /config/migration_services.yaml " +
                                                "--stage ${stage} " +
                                                "--snapshot-name ${params.SNAPSHOT_NAME} " +
                                                "--snapshot-repo ${params.SNAPSHOT_REPO_NAME} " +
                                                "--backfill-scale ${params.BACKFILL_SCALE} " +
                                                "--unique-id ${testUniqueId}"
                                
                                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", duration: 3600, roleSessionName: 'jenkins-session') {
                                        // Execute test with metrics collection
                                        sh "./awsRunIntegTests.sh --command '${testCommand}' --stage ${stage}"
                                        
                                        // Retrieve metrics file from ECS container
                                        sh "./awsRunIntegTests.sh --retrieve-file ${remoteMetricsPath} --local-path ${localMetricsPath} --stage ${stage}"
                                    }
                                }
                                
                                // Store paths for metrics collection stage
                                env.REMOTE_METRICS_PATH = remoteMetricsPath
                                env.LOCAL_METRICS_PATH = localMetricsPath
                                env.TEST_UNIQUE_ID = testUniqueId
                            }
                        }
                    }
                }
            }

            stage('Metrics Collection and Plotting') {
                steps {
                    timeout(time: 15, unit: 'MINUTES') {
                        dir('test') {
                            script {
                                echo "Starting metrics plotting for commit: ${env.CURRENT_COMMIT} (${env.COMMIT_DATE})"
                                
                                try {
                                    if (fileExists(env.LOCAL_METRICS_PATH)) {
                                        echo "Metrics file found at ${env.LOCAL_METRICS_PATH}"
                                        
                                        // Verify file content
                                        def fileContent = readFile(env.LOCAL_METRICS_PATH)
                                        if (!fileContent.trim()) {
                                            echo "ERROR: Metrics file is empty"
                                            return
                                        }
                                        
                                        def lines = fileContent.split('\n')
                                        echo "CSV header: ${lines[0]}"
                                        if (lines.length > 1) {
                                            echo "Data row: ${lines[1]}"
                                        }
                                        
                                        // Add commit info to CSV for x-axis labeling
                                        def commitLabel = "${env.CURRENT_COMMIT} (${env.COMMIT_DATE})"
                                        def enhancedContent = fileContent.replaceFirst('\n', ",Commit\n")
                                        if (lines.length > 1) {
                                            enhancedContent = enhancedContent.replaceFirst('(?m)^([^\\n]+)$', '$1,' + commitLabel)
                                        }
                                        writeFile file: env.LOCAL_METRICS_PATH, text: enhancedContent
                                        
                                        // Plot Migration Duration Trend
                                        plot csvFileName: 'backfill_metrics_duration.csv',
                                             csvSeries: [[
                                                 file: env.LOCAL_METRICS_PATH, 
                                                 exclusionValues: 'Duration (min)', 
                                                 displayTableFlag: false, 
                                                 inclusionFlag: 'INCLUDE_BY_STRING', 
                                                 url: ''
                                             ]],
                                             group: 'Migration Performance Metrics',
                                             title: 'Migration Duration Trend',
                                             style: 'line',
                                             exclZero: false,
                                             keepRecords: true,
                                             logarithmic: false,
                                             yaxis: 'Duration (minutes)',
                                             hasLegend: true
                                        
                                        // Plot Data Size Transferred Trend
                                        plot csvFileName: 'backfill_metrics_size.csv',
                                             csvSeries: [[
                                                 file: env.LOCAL_METRICS_PATH, 
                                                 exclusionValues: 'Size Transferred (GB)', 
                                                 displayTableFlag: false, 
                                                 inclusionFlag: 'INCLUDE_BY_STRING', 
                                                 url: ''
                                             ]],
                                             group: 'Migration Performance Metrics',
                                             title: 'Data Size Transferred Trend',
                                             style: 'line',
                                             exclZero: false,
                                             keepRecords: true,
                                             logarithmic: false,
                                             yaxis: 'Size (GB)',
                                             hasLegend: true
                                        
                                        // Plot Per-Worker Throughput Trend
                                        plot csvFileName: 'backfill_metrics_per_worker_throughput.csv',
                                             csvSeries: [[
                                                 file: env.LOCAL_METRICS_PATH, 
                                                 exclusionValues: 'Reindexing Throughput Per Worker (MiB/s)', 
                                                 displayTableFlag: false, 
                                                 inclusionFlag: 'INCLUDE_BY_STRING', 
                                                 url: ''
                                             ]],
                                             group: 'Migration Performance Metrics',
                                             title: 'Per-Worker Throughput Trend',
                                             style: 'line',
                                             exclZero: false,
                                             keepRecords: true,
                                             logarithmic: false,
                                             yaxis: 'Throughput (MiB/s)',
                                             hasLegend: true
                                        
                                        // Plot Total Throughput Trend
                                        plot csvFileName: 'backfill_metrics_total_throughput.csv',
                                             csvSeries: [[
                                                 file: env.LOCAL_METRICS_PATH, 
                                                 exclusionValues: 'Reindexing Throughput Total (MiB/s)', 
                                                 displayTableFlag: false, 
                                                 inclusionFlag: 'INCLUDE_BY_STRING', 
                                                 url: ''
                                             ]],
                                             group: 'Migration Performance Metrics',
                                             title: 'Total Throughput Trend',
                                             style: 'line',
                                             exclZero: false,
                                             keepRecords: true,
                                             logarithmic: false,
                                             yaxis: 'Total Throughput (MiB/s)',
                                             hasLegend: true
                                        
                                        echo "Performance metrics plotting completed successfully"
                                        
                                    } else {
                                        echo "ERROR: Metrics file not found at ${env.LOCAL_METRICS_PATH}"
                                    }
                                } catch (Exception e) {
                                    echo "ERROR: Exception during plotting: ${e.message}"
                                    e.printStackTrace()
                                }
                                
                                // Archive metrics file
                                archiveArtifacts artifacts: env.LOCAL_METRICS_PATH, allowEmptyArchive: true
                            }
                        }
                    }
                }
            }
        }

        post {
            always {
                timeout(time: 30, unit: 'MINUTES') {
                    dir('test') {
                        script {
                            echo "Starting cleanup process..."
                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", duration: 3600, roleSessionName: 'jenkins-session') {
                                    // Cleanup Migration Assistant
                                    sh "./awsEnhancedE2ESolutionSetup.sh --cleanup-migration --stage ${stage} || echo 'Migration cleanup completed with warnings'"
                                    
                                    // Cleanup Target Cluster
                                    sh "./awsEnhancedE2ESolutionSetup.sh --cleanup-target --stage ${stage} || echo 'Target cleanup completed with warnings'"
                                }
                            }
                            echo "Cleanup process completed"
                        }
                    }
                }
            }
        }
    }
}
