import groovy.json.JsonOutput

def call(Map params) {
    echo "Starting Document Multiplication E2E Test"
    echo "Parameters received: ${params}"
    
    def processedParams = processParameters(params)
    def contexts = generateCDKContexts(processedParams)
    
    DocMultiplicationPipeline([
        params: processedParams,
        contexts: contexts,
        workerAgent: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host'
    ])
}

def processParameters(Map params) {
    echo "Processing and validating parameters..."
    
    // Map cluster version to internal values with enhanced configuration
    def versionMapping = mapClusterVersionWithDetails(params.CLUSTER_VERSION, params.VERSION)
    
    // Generate unique test identifier
    def time = new Date().getTime()
    def testUniqueId = "document_multiplier_${time}_${currentBuild.number}"
    
    // Build processed parameter map
    def processed = [
        gitRepoUrl: params.GIT_REPO_URL ?: 'https://github.com/jugal-chauhan/opensearch-migrations.git',
        gitBranch: params.GIT_BRANCH ?: 'pytest-doc-multiplier',
        stage: params.STAGE ?: 'dev',
        region: params.SNAPSHOT_REGION ?: 'us-west-2',
        clusterVersion: params.CLUSTER_VERSION ?: 'es7x',
        engineVersion: versionMapping.engineVersion,
        distVersion: versionMapping.distVersion,
        dataNodeType: versionMapping.dataNodeType,
        dedicatedManagerNodeType: versionMapping.dedicatedManagerNodeType,
        dataNodeCount: versionMapping.dataNodeCount,
        dedicatedMasterEnabled: versionMapping.dedicatedMasterEnabled,
        masterNodeCount: versionMapping.masterNodeCount,
        ebsEnabled: versionMapping.ebsEnabled,
        ebsVolumeSize: versionMapping.ebsVolumeSize,
        nodeToNodeEncryption: versionMapping.nodeToNodeEncryption,
        snapshotBucketPrefix: params.FINAL_SNAPSHOT_BUCKET_PREFIX ?: 'migrations-jenkins-snapshot-',
        s3DirectoryPrefix: params.FINAL_SNAPSHOT_BUCKET_PREFIX ?: "large-snapshot-${params.CLUSTER_VERSION}-",
        numShards: params.NUM_SHARDS ?: '10',
        indexName: params.INDEX_NAME ?: 'basic_index',
        batchCount: params.BATCH_COUNT ?: '5',
        docsPerBatch: params.DOCS_PER_BATCH ?: '50',
        multiplicationFactor: params.MULTIPLICATION_FACTOR ?: '1000',
        rfsWorkers: params.RFS_WORKERS ?: '5',
        testUniqueId: testUniqueId,
        migrationContextId: 'document-multiplier-rfs',
        skipCleanup: params.SKIP_CLEANUP ?: false,
        debugMode: params.DEBUG_MODE ?: false
    ]
    
    // Validate critical parameters
    validateParameters(processed)
    
    echo "Parameters processed successfully"
    echo "Engine Version: ${processed.engineVersion}"
    echo "Distribution Version: ${processed.distVersion}"
    echo "Data Node Type: ${processed.dataNodeType}"
    echo "Data Node Count: ${processed.dataNodeCount}"
    echo "Stage: ${processed.stage}"
    echo "Region: ${processed.region}"
    echo "Test Unique ID: ${processed.testUniqueId}"
    
    return processed
}

def mapClusterVersionWithDetails(String clusterVersion, String version) {
    echo "Mapping cluster version with enhanced details: ${clusterVersion}"
    
    def mapping = [:]
    
    switch (clusterVersion) {
        case 'es5x':
            mapping = [
                engineVersion: "ES_5.6",
                distVersion: "5.6",
                dataNodeType: "r5.4xlarge.search",
                dedicatedManagerNodeType: "m4.xlarge.search",
                dataNodeCount: 6,
                dedicatedMasterEnabled: true,
                masterNodeCount: 4,
                ebsEnabled: true,
                ebsVolumeSize: 300,
                nodeToNodeEncryption: false
            ]
            break
        case 'es6x':
            mapping = [
                engineVersion: "ES_6.8",
                distVersion: "6.8",
                dataNodeType: "r5.4xlarge.search",
                dedicatedManagerNodeType: "m5.xlarge.search",
                dataNodeCount: 6,
                dedicatedMasterEnabled: true,
                masterNodeCount: 4,
                ebsEnabled: true,
                ebsVolumeSize: 300,
                nodeToNodeEncryption: true
            ]
            break
        case 'es7x':
            mapping = [
                engineVersion: "ES_7.10",
                distVersion: "7.10",
                dataNodeType: "r6gd.4xlarge.search",
                dedicatedManagerNodeType: "m6g.xlarge.search",
                dataNodeCount: 6,
                dedicatedMasterEnabled: true,
                masterNodeCount: 4,
                ebsEnabled: false,
                ebsVolumeSize: null,
                nodeToNodeEncryption: true
            ]
            break
        case 'os1x':
            mapping = [
                engineVersion: "OS_1.3",
                distVersion: "1.3",
                dataNodeType: "r6g.4xlarge.search",
                dedicatedManagerNodeType: "m6g.xlarge.search",
                dataNodeCount: 6,
                dedicatedMasterEnabled: true,
                masterNodeCount: 4,
                ebsEnabled: true,
                ebsVolumeSize: 1024,
                nodeToNodeEncryption: true
            ]
            break
        case 'os2x':
            mapping = [
                engineVersion: "OS_2.19",
                distVersion: "2.19",
                dataNodeType: "r6g.4xlarge.search",
                dedicatedManagerNodeType: "m6g.xlarge.search",
                dataNodeCount: 6,
                dedicatedMasterEnabled: true,
                masterNodeCount: 4,
                ebsEnabled: true,
                ebsVolumeSize: 1024,
                nodeToNodeEncryption: true
            ]
            break
        default:
            error("Unsupported cluster version: ${clusterVersion}. Supported versions: es5x, es6x, es7x, os1x, os2x")
    }
    
    return mapping
}

def validateParameters(Map params) {
    echo "Validating parameters..."
    
    // Validate required parameters
    def requiredParams = ['gitRepoUrl', 'gitBranch', 'stage', 'region', 'engineVersion']
    requiredParams.each { param ->
        if (!params[param]) {
            error("Required parameter missing: ${param}")
        }
    }
    
    // Validate numeric parameters
    def numericParams = ['numShards', 'docsToIngest', 'multiplicationFactor', 'rfsWorkers', 'dataNodeCount']
    numericParams.each { param ->
        if (params[param] != null) {
            try {
                Integer.parseInt(params[param].toString())
            } catch (NumberFormatException e) {
                error("Parameter ${param} must be a valid integer: ${params[param]}")
            }
        }
    }
    
    // Validate stage name (no special characters)
    if (!params.stage.matches(/^[a-zA-Z0-9-]+$/)) {
        error("Stage name must contain only alphanumeric characters and hyphens: ${params.stage}")
    }
    
    echo "Parameter validation completed"
}

def generateCDKContexts(Map params) {
    echo "Generating CDK contexts..."
    
    // Generate source cluster context for AWS Solutions CDK
    def sourceContext = generateSourceContext(params)
    
    // Generate migration context with enhanced configuration
    def migrationContext = generateEnhancedMigrationContext(params)
    
    def contexts = [
        source: sourceContext,
        migration: migrationContext
    ]
    
    echo "CDK contexts generated successfully"
    return contexts
}

def generateSourceContext(Map params) {
    echo "Generating source cluster context..."
    
    def domainName = "source-${params.clusterVersion}-jenkins-test"
    def clusterId = "${params.clusterVersion}"
    
    def sourceContext = [
        "stage": params.stage,
        "vpcAZCount": 2,
        "clusters": [
            [
                "clusterId": clusterId,
                "clusterName": domainName,
                "clusterVersion": params.engineVersion,
                "clusterType": "OPENSEARCH_MANAGED_SERVICE",
                "dataNodeCount": params.dataNodeCount,
                "dataNodeType": params.dataNodeType,
                "dedicatedManagerEnabled": params.dedicatedMasterEnabled ?: true,
                "dedicatedManagerNodeCount": params.masterNodeCount ?: 4,
                "dedicatedManagerNodeType": params.dedicatedManagerNodeType,
                "openAccessPolicyEnabled": true,
                "domainRemovalPolicy": "DESTROY",
                "enforceHTTPS": true,
                "nodeToNodeEncryptionEnabled": params.nodeToNodeEncryption,
                "encryptionAtRestEnabled": true,
                "ebsEnabled": params.ebsEnabled,
                "ebsVolumeSize": params.ebsVolumeSize ?: 20,
                "ebsVolumeType": "GP3",
                "vpcSecurityGroupEnabled": true,
                "vpcSecurityGroupAllowAllInbound": true
            ]
        ]
    ]
    
    echo "Source cluster will be created with:"
    echo "  - Domain Name: ${domainName} (${domainName.length()} characters)"
    echo "  - Engine Version: ${params.engineVersion}"
    echo "  - Data Node Count: ${params.dataNodeCount}"
    echo "  - Data Node Type: ${params.dataNodeType}"
    echo "  - EBS Enabled: ${params.ebsEnabled}"
    if (params.ebsVolumeSize) {
        echo "  - EBS Volume Size: ${params.ebsVolumeSize} GB"
    }
    
    return sourceContext
}


def generateEnhancedMigrationContext(Map params) {
    echo "Generating enhanced migration infrastructure context..."
    
    def docTransformerPath = "/shared-logs-output/test-transformations/transformation.json"
    
    def migrationContext = [
        "document-multiplier-rfs": [
            "stage": params.stage,
            "region": params.region,
            "artifactBucketRemovalPolicy": "DESTROY",
            "captureProxyServiceEnabled": false,
            "targetClusterProxyServiceEnabled": false,
            "trafficReplayerServiceEnabled": false,
            "reindexFromSnapshotServiceEnabled": true,
            "reindexFromSnapshotExtraArgs": "--doc-transformer-config-file ${docTransformerPath} --source-version ${params.engineVersion}",
            "sourceClusterDeploymentEnabled": false,
            "sourceClusterDisabled": true,
            "vpcEnabled": true,
            "vpcAZCount": 2,
            "migrationAssistanceEnabled": true,
            "replayerOutputEFSRemovalPolicy": "DESTROY",
            "migrationConsoleServiceEnabled": true,
            "otelCollectorEnabled": true,
            
            // Test-specific configuration
            "testConfiguration": [
                "indexName": params.indexName,
                "numShards": params.numShards,
                "docsPerBatch": params.docsPerBatch,
                "multiplicationFactor": params.multiplicationFactor,
                "rfsWorkers": params.rfsWorkers,
                "snapshotBucketPrefix": params.snapshotBucketPrefix,
                "s3DirectoryPrefix": params.s3DirectoryPrefix,
                "testUniqueId": params.testUniqueId
            ]
        ]
    ]
    
    echo "Enhanced migration context generated with:"
    echo "  - Engine Version: ${params.engineVersion}"
    echo "  - Data Node Type: ${params.dataNodeType}"
    echo "  - Data Node Count: ${params.dataNodeCount}"
    echo "  - EBS Enabled: ${params.ebsEnabled}"
    if (params.ebsVolumeSize) {
        echo "  - EBS Volume Size: ${params.ebsVolumeSize} GB"
    }
    echo "  - Node-to-Node Encryption: ${params.nodeToNodeEncryption}"
    echo "  - Index Name: ${params.indexName}"
    echo "  - Shards: ${params.numShards}"
    echo "  - Multiplication Factor: ${params.multiplicationFactor}"
    echo "  - RFS Workers: ${params.rfsWorkers}"
    echo "  - Test Unique ID: ${params.testUniqueId}"
    
    return migrationContext
}
