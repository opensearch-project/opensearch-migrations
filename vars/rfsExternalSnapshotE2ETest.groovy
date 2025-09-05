// RFS External Snapshot E2E Test Configuration
// This file contains the configuration, context definitions, and plot callback for the external snapshot performance test

// Helper function to extract source version from S3 URI
def extractSourceVersionFromS3Uri(s3Uri) {
    // Remove trailing slash and get last 4 characters
    def cleanUri = s3Uri.replaceAll(/\/$/, '')
    def versionSuffix = cleanUri.substring(cleanUri.length() - 4)
    
    switch(versionSuffix) {
        case 'es5x': return 'ES_5.6'
        case 'es6x': return 'ES_6.8'
        case 'es7x': return 'ES_7.10'
        case 'os1x': return 'OS_1.3'
        case 'os2x': return 'OS_2.19'
        default: return 'ES_7.10' // fallback
    }
}

def call(Map config = [:]) {
    def migrationContextId = 'migration-rfs-external-snapshot'
    def stageId = config.STAGE ?: 'rfs-external-snapshot-integ'
    def lockResourceName = config.lockResourceName ?: stageId
    def sourceContextId = 'source-empty'
    
    // Define the metrics file paths
    def testDir = "/root/lib/integ_test/integ_test"
    def testUniqueId = config.testUniqueId ?: "integ_full_${new Date().getTime()}_${currentBuild.number}"
    def remoteMetricsPath = "${testDir}/reports/${testUniqueId}/backfill_metrics.csv"
    def metricsOutputDir = "backfill-metrics"
    def localMetricsPath = "${metricsOutputDir}/backfill_metrics.csv"
    
    // Define metrics to plot
    def metricsToPlot = [
        [field: 'Duration (min)', title: 'Migration Duration Trend', yaxis: 'Duration (minutes)', style: 'line', logarithmic: false],
        [field: 'Reindexing Throughput Total (MiB/s)', title: 'Total Throughput Trend', yaxis: 'Total Throughput (MiB/s)', style: 'line', logarithmic: false],
        [field: 'Reindexing Throughput Per Worker (MiB/s)', title: 'Per-Worker Throughput Trend', yaxis: 'Throughput (MiB/s)', style: 'line', logarithmic: false],
        [field: 'Size Transferred (GB)', title: 'Data Size Transferred Trend', yaxis: 'Size (GB)', style: 'line', logarithmic: false],
    ]
    
    // Plot metrics callback
    def plotMetricsCallback = { ->
        echo "Starting metrics plotting callback"
        
        try {
            if (fileExists(localMetricsPath)) {
                echo "Metrics file found at ${localMetricsPath}"
                
                sh """
                    echo "File size: \$(du -h ${localMetricsPath} | cut -f1)"
                    echo "File contents:"
                    cat ${localMetricsPath}
                """
                
                def fileContent = readFile(localMetricsPath)
                if (!fileContent.trim()) {
                    echo "ERROR: Metrics file is empty"
                    return
                }
                
                def lines = fileContent.split('\n')
                echo "Number of lines in CSV: ${lines.size()}"
                if (lines.size() < 2) {
                    echo "ERROR: CSV file does not have enough data (header + at least one data row)"
                    return
                }
                
                echo "CSV header: ${lines[0]}"
                echo "First data row: ${lines[1]}"
                
                // Add commit info to CSV for x-axis labeling
                def commitLabel = "${env.TEST_COMMIT_SHORT} (${env.COMMIT_DATE})"
                def enhancedContent = fileContent.replaceFirst('\n', ",Commit\n")
                if (lines.size() > 1) {
                    enhancedContent = enhancedContent.replaceFirst('(?m)^([^\\n]+)$', '$1,' + commitLabel)
                }
                writeFile file: localMetricsPath, text: enhancedContent
                
                // Plot each metric from the static list
                metricsToPlot.each { metric ->
                    echo "Plotting ${metric.title} (Field: ${metric.field})"

                    def uniqueCsvName = "backfill_metrics_" + metric.field.replaceAll(/[^A-Za-z0-9]/, '') + ".csv"
                    
                    plot csvFileName: uniqueCsvName,
                         csvSeries: [[file: localMetricsPath, exclusionValues: metric.field, displayTableFlag: false, inclusionFlag: 'INCLUDE_BY_STRING', url: '']],
                         group: 'Migration Performance Metrics',
                         title: metric.title,
                         style: metric.style,
                         exclZero: false,
                         keepRecords: true,
                         logarithmic: metric.logarithmic,
                         yaxis: metric.yaxis,
                         hasLegend: true
                }
                echo "Performance metrics plotting completed successfully"
            } else {
                echo "ERROR: Metrics file not found at ${localMetricsPath}, skipping plot"
                sh """
                    if [ -d "\$(dirname ${localMetricsPath})" ]; then
                        echo "Directory exists, listing contents:"
                        ls -la \$(dirname ${localMetricsPath})
                    else
                        echo "Directory does not exist: \$(dirname ${localMetricsPath})"
                    fi
                """
            }
        } catch (Exception e) {
            echo "ERROR: Exception occurred during plotting: ${e.message}"
            e.printStackTrace()
        }
    }
    
    // Get the source version from the S3 URI
    def sourceVersion = extractSourceVersionFromS3Uri(config.SNAPSHOT_S3_URI ?: 's3://bucket/large-snapshot-es7x/')
    
    // Migration context with target cluster and external snapshot configuration
    def migration_cdk_context = """
        {
          "migration-rfs-external-snapshot": {
            "stage": "<STAGE>",
            "vpcId": "<VPC_ID>",
            "engineVersion": "<TARGET_VERSION>",
            "domainName": "os-cluster-<STAGE>",
            "dataNodeCount": "<TARGET_DATA_NODE_COUNT>",
            "dataNodeType": "<TARGET_DATA_NODE_TYPE>",
            "ebsEnabled": "<TARGET_EBS_ENABLED>",
            "dedicatedManagerNodeCount": "<TARGET_MANAGER_NODE_COUNT>",
            "dedicatedManagerNodeType": "<TARGET_MANAGER_NODE_TYPE>",
            "domainAZCount": 2,
            "openAccessPolicyEnabled": true,
            "domainRemovalPolicy": "DESTROY",
            "artifactBucketRemovalPolicy": "DESTROY",
            "trafficReplayerServiceEnabled": false,
            "reindexFromSnapshotServiceEnabled": true,
            "tlsSecurityPolicy": "TLS_1_2",
            "enforceHTTPS": true,
            "nodeToNodeEncryptionEnabled": true,
            "encryptionAtRestEnabled": true,
            "vpcEnabled": true,
            "vpcAZCount": 2,
            "mskAZCount": 2,
            "migrationAssistanceEnabled": true,
            "replayerOutputEFSRemovalPolicy": "DESTROY",
            "migrationConsoleServiceEnabled": true,
            "otelCollectorEnabled": true,
            "captureProxyServiceEnabled": false,
            "targetClusterProxyServiceEnabled": false,
            "captureProxyDesiredCount": 0,
            "targetClusterProxyDesiredCount": 0,
            "sourceCluster": {
              "disabled": true,
              "version": "${sourceVersion}"
            },
            "snapshot": {
              "snapshotName": "<SNAPSHOT_NAME>",
              "snapshotRepoName": "<SNAPSHOT_REPO_NAME>",
              "s3Uri": "<SNAPSHOT_S3_URI>",
              "s3Region": "<SNAPSHOT_REGION>"
            }
          }
        }
    """

    // Call the main pipeline with configuration and parameters
    rfsExternalSnapshotE2EPipeline([
        migrationContext: migration_cdk_context,
        migrationContextId: migrationContextId,
        stageId: stageId,
        lockResourceName: lockResourceName,
        testUniqueId: testUniqueId,
        remoteMetricsPath: remoteMetricsPath,
        localMetricsPath: localMetricsPath,
        metricsOutputDir: metricsOutputDir,
        plotMetricsCallback: plotMetricsCallback,
        // Fix: Pass parameters in the params map that the pipeline expects
        params: [
            gitRepoUrl: config.GIT_REPO_URL,
            gitBranch: config.GIT_BRANCH,
            stage: config.STAGE,
            backfillScale: config.BACKFILL_SCALE,
            customCommit: config.CUSTOM_COMMIT,
            snapshotS3Uri: config.SNAPSHOT_S3_URI,
            snapshotName: config.SNAPSHOT_NAME,
            region: config.SNAPSHOT_REGION,
            snapshotRepoName: config.SNAPSHOT_REPO_NAME,
            targetVersion: config.TARGET_VERSION,
            targetDataNodeCount: config.TARGET_DATA_NODE_COUNT,
            targetDataNodeType: config.TARGET_DATA_NODE_TYPE,
            targetManagerNodeCount: config.TARGET_MANAGER_NODE_COUNT,
            targetManagerNodeType: config.TARGET_MANAGER_NODE_TYPE,
            targetEbsEnabled: config.TARGET_EBS_ENABLED,
            testUniqueId: testUniqueId,
            skipCleanup: false,
            debugMode: false
        ]
    ])
}
