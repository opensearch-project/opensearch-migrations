/**
 * EKS BYOS (Bring Your Own Snapshot) Integration Pipeline
 *
 * This pipeline tests migration from an existing S3 snapshot to a target OpenSearch cluster.
 * No source cluster is deployed - only a target cluster in Amazon OpenSearch Service.
 *
 * Modeled after eksIntegPipeline.groovy — uses aws-bootstrap.sh for EKS/MA deployment.
 *
 * Required parameters:
 * - S3_REPO_URI: Full S3 URI to snapshot repository (example: s3://bucket/folder/)
 * - SNAPSHOT_NAME: Name of the snapshot
 * - SOURCE_VERSION: Version of the cluster that created the snapshot (ES_5.6, ES_6.8, ES_7.10)
 * - TARGET_VERSION: Target OpenSearch version (OS_2.19, OS_3.1)
 */

import groovy.json.JsonOutput

static def expandVersionString(String input) {
    def trimmed = input.trim()
    def pattern = ~/^(ES|OS)_(\d+)\.(\d+)$/
    def matcher = trimmed =~ pattern
    if (!matcher.matches()) {
        error("Invalid version string format: '${input}'. Expected something like ES_7.10 or OS_2.19")
    }
    def prefix = matcher[0][1]
    def major  = matcher[0][2]
    def minor  = matcher[0][3]
    def name   = (prefix == 'ES') ? 'elasticsearch' : 'opensearch'
    return "${name}-${major}-${minor}"
}

def call(Map config = [:]) {
    def defaultStageId = config.defaultStageId ?: "eksbyos"
    def jobName = config.jobName ?: "byos-eks-integ-test"
    def clusterContextFilePath = "tmp/cluster-context-byos-${currentBuild.number}.json"
    def testIds = config.testIds ?: "0010"
    def sourceVersion = config.sourceVersion ?: ""
    def targetVersion = config.targetVersion ?: ""
    def targetClusterSize = config.targetClusterSize ?: ""
    def targetClusterSizes = [
        'default': [
            dataNodeType: "r6g.large.search",
            dedicatedManagerNodeType: "m6g.large.search",
            dataNodeCount: 2,
            dedicatedManagerNodeCount: 0,
            ebsVolumeSize: 100
        ],
        'large': [
            dataNodeType: "r6g.8xlarge.search",
            dedicatedManagerNodeType: "m6g.2xlarge.search",
            dataNodeCount: 24,
            dedicatedManagerNodeCount: 4,
            ebsVolumeSize: 2048
        ]
    ]
    pipeline {
        agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }
        parameters {
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/opensearch-project/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Git branch to use for repository')
            string(name: 'STAGE', defaultValue: "${defaultStageId}", description: 'Stage name for deployment environment')
            string(name: 'RFS_WORKERS', defaultValue: '1', description: 'Number of RFS worker pods for document backfill (podReplicas)')
            // Snapshot configuration
            string(name: 'S3_REPO_URI', defaultValue: 's3://migrations-snapshots-library-us-east-1/large-snapshot-es5x/', description: 'Full S3 URI to snapshot repository (e.g., s3://bucket/folder/)')
            string(name: 'REGION', defaultValue: 'us-east-1', description: 'AWS region for deployment and snapshot bucket')
            string(name: 'SNAPSHOT_NAME', defaultValue: 'large-snapshot', description: 'Name of the snapshot')
            string(name: 'TEST_IDS', defaultValue: '0010', description: 'Test IDs to execute (comma separated, e.g., "0010" or "0010,0011")')
            string(name: 'MONITOR_RETRY_LIMIT', defaultValue: '900', description: 'Max retries for workflow monitoring (fixed 60-second interval between retries)')
            choice(
                name: 'SOURCE_VERSION',
                choices: ['ES_1.5', 'ES_2.4', 'ES_5.6', 'ES_6.8', 'ES_7.10', 'ES_8.19', 'OS_1.3', 'OS_2.19'],
                description: 'Version of the cluster that created the snapshot'
            )
            // Target cluster configuration
            choice(
                name: 'TARGET_VERSION',
                choices: ['OS_2.19', 'OS_3.1'],
                description: 'Target OpenSearch version'
            )
            choice(
                name: 'TARGET_CLUSTER_SIZE',
                choices: ['default', 'large'],
                description: 'Target cluster size (default: 2x r6g.large, large: 24x r6g.8xlarge with dedicated masters)'
            )
        }
        options {
            lock(label: params.STAGE, quantity: 1, variable: 'maStageName')
            timeout(time: 18, unit: 'HOURS')
            buildDiscarder(logRotator(daysToKeepStr: '30'))
            skipDefaultCheckout(true)
        }
        triggers {
            GenericTrigger(
                genericVariables: [
                    [key: 'GIT_REPO_URL', value: '$.GIT_REPO_URL'],
                    [key: 'GIT_BRANCH', value: '$.GIT_BRANCH'],
                    [key: 'job_name', value: '$.job_name']
                ],
                tokenCredentialId: 'jenkins-migrations-generic-webhook-token',
                causeString: 'Triggered by webhook',
                regexpFilterExpression: "^$jobName\$",
                regexpFilterText: "\$job_name",
            )
        }
        stages {
            stage('Checkout') {
                steps {
                    checkoutStep(branch: params.GIT_BRANCH, repo: params.GIT_REPO_URL)
                }
            }

            stage('Validate Parameters') {
                steps {
                    script {
                        if (!(params.REGION ==~ /^[a-z0-9-]+$/)) {
                            error("Invalid AWS region '${params.REGION}'")
                        }
                        if (!(params.STAGE ==~ /^[A-Za-z0-9-]+$/)) {
                            error("Invalid STAGE '${params.STAGE}'")
                        }
                    }
                }
            }

            stage('Test Caller Identity') {
                steps {
                    script {
                        sh 'aws sts get-caller-identity'
                    }
                }
            }

            stage('Build') {
                steps {
                    timeout(time: 1, unit: 'HOURS') {
                        script {
                            sh './gradlew clean build --no-daemon --stacktrace'
                        }
                    }
                }
            }

            stage('Deploy AOS Target Cluster') {
                steps {
                    timeout(time: 45, unit: 'MINUTES') {
                        dir('test') {
                            script {
                                env.sourceVer = sourceVersion ?: params.SOURCE_VERSION
                                env.targetVer = targetVersion ?: params.TARGET_VERSION
                                env.targetClusterSize = targetClusterSize ?: params.TARGET_CLUSTER_SIZE

                                def sizeConfig = targetClusterSizes[env.targetClusterSize]
                                deployTargetClusterOnly(
                                    stage: "${maStageName}",
                                    clusterContextFilePath: "${clusterContextFilePath}",
                                    targetVer: env.targetVer,
                                    sizeConfig: sizeConfig
                                )
                            }
                        }
                    }
                }
            }

            stage('Deploy & Bootstrap MA') {
                steps {
                    timeout(time: 150, unit: 'MINUTES') {
                        script {
                            env.STACK_NAME_SUFFIX = "${maStageName}-${params.REGION}"
                            def clusterDetails = readJSON text: env.clusterDetailsJson
                            def targetCluster = clusterDetails.target
                            def vpcId = targetCluster.vpcId
                            def subnetIds = "${targetCluster.subnetIds}"

                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: params.REGION, duration: 3600, roleSessionName: 'jenkins-session') {
                                    sh """
                                        ./deployment/k8s/aws/aws-bootstrap.sh \
                                          --deploy-import-vpc-cfn \
                                          --build-cfn \
                                          --stack-name "Migration-Assistant-Infra-Import-VPC-eks-${env.STACK_NAME_SUFFIX}" \
                                          --vpc-id "${vpcId}" \
                                          --subnet-ids "${subnetIds}" \
                                          --stage "${maStageName}" \
                                          --eks-access-principal-arn "arn:aws:iam::\${MIGRATIONS_TEST_ACCOUNT_ID}:role/JenkinsDeploymentRole" \
                                          --build-images \
                                          --build-chart-and-dashboards \
                                          --base-dir "\$(pwd)" \
                                          --skip-console-exec \
                                          --region ${params.REGION} \
                                          2>&1 | while IFS= read -r line; do printf '%s | %s\\n' "\$(date '+%H:%M:%S')" "\$line"; done; exit \${PIPESTATUS[0]}
                                    """

                                    // Capture env vars for later stages and cleanup
                                    def rawOutput = sh(
                                        script: """
                                          aws cloudformation describe-stacks \
                                          --stack-name Migration-Assistant-Infra-Import-VPC-eks-${env.STACK_NAME_SUFFIX} \
                                          --query "Stacks[0].Outputs[?OutputKey=='MigrationsExportString'].OutputValue" \
                                          --output text
                                        """,
                                        returnStdout: true
                                    ).trim()
                                    if (!rawOutput) {
                                        error("CloudFormation stack output is empty — stack may not have deployed correctly.")
                                    }
                                    def exportsMap = rawOutput.split(';')
                                            .collect { it.trim().replaceFirst(/^export\s+/, '') }
                                            .findAll { it.contains('=') }
                                            .collectEntries {
                                                def (key, value) = it.split('=', 2)
                                                [(key): value]
                                            }
                                    env.registryEndpoint = exportsMap['MIGRATIONS_ECR_REGISTRY']
                                    env.eksClusterName = exportsMap['MIGRATIONS_EKS_CLUSTER_NAME']
                                    env.clusterSecurityGroup = exportsMap['EKS_CLUSTER_SECURITY_GROUP']
                                }
                            }
                        }
                    }
                }
            }

            stage('Post-Cluster Setup') {
                steps {
                    timeout(time: 15, unit: 'MINUTES') {
                        script {
                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: params.REGION, duration: 1200, roleSessionName: 'jenkins-session') {
                                    def clusterDetails = readJSON text: env.clusterDetailsJson
                                    def targetCluster = clusterDetails.target
                                    def targetVersionExpanded = expandVersionString("${env.targetVer}")

                                    // Target configmap (no source cluster in BYOS)
                                    writeJSON file: '/tmp/target-cluster-config.json', json: [
                                            endpoint: targetCluster.endpoint,
                                            allow_insecure: true,
                                            sigv4: [
                                                    region: params.REGION,
                                                    service: "es"
                                            ],
                                            version: env.targetVer
                                    ]
                                    sh """
                                      kubectl create configmap target-${targetVersionExpanded}-migration-config \
                                        --from-file=cluster-config=/tmp/target-cluster-config.json \
                                        --namespace ma --dry-run=client -o yaml | kubectl apply -f -

                                      kubectl -n ma get configmap target-${targetVersionExpanded}-migration-config -o yaml
                                    """

                                    // Modify target security group to allow EKS cluster security group
                                    sh """
                                      exists=\$(aws ec2 describe-security-groups \
                                        --group-ids $targetCluster.securityGroupId \
                                        --query "SecurityGroups[0].IpPermissions[?UserIdGroupPairs[?GroupId=='$env.clusterSecurityGroup']]" \
                                        --output text)

                                      if [ -z "\$exists" ]; then
                                        echo "Ingress rule not found. Adding..."
                                        aws ec2 authorize-security-group-ingress \
                                          --group-id $targetCluster.securityGroupId \
                                          --protocol -1 \
                                          --port -1 \
                                          --source-group $env.clusterSecurityGroup
                                      else
                                        echo "Ingress rule already exists. Skipping."
                                      fi
                                    """
                                }
                            }
                        }
                    }
                }
            }

            stage('Run BYOS Migration Test') {
                steps {
                    timeout(time: 12, unit: 'HOURS') {
                        script {
                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: params.REGION, duration: 43200, roleSessionName: 'jenkins-session') {
                                    // Wait for migration-console pod to be ready
                                    sh """
                                        echo "Waiting for migration-console pod to be ready"
                                        kubectl wait --for=condition=Ready pod/migration-console-0 -n ma --timeout=600s
                                        echo "Migration console pod is ready"
                                    """
                                    sh """
                                        echo "Applying workflow template"
                                        kubectl apply -f ${WORKSPACE}/migrationConsole/lib/integ_test/testWorkflows/fullMigrationImportedClusters.yaml -n ma
                                        echo "Workflow template applied successfully"
                                    """

                                    // Normalize S3 URI - ensure it ends with /
                                    def s3RepoUri = params.S3_REPO_URI
                                    if (!s3RepoUri.endsWith('/')) {
                                        s3RepoUri = s3RepoUri + '/'
                                    }

                                    // Resolve test IDs
                                    def testIdsResolved = testIds ?: params.TEST_IDS

                                    // Run the BYOS test with env vars passed via file to avoid logging
                                    sh """
                                        cat > /tmp/byos-env.sh << 'ENVEOF'
export BYOS_SNAPSHOT_NAME='${params.SNAPSHOT_NAME}'
export BYOS_S3_REPO_URI='${s3RepoUri}'
export BYOS_S3_REGION='${params.REGION}'
export BYOS_POD_REPLICAS='${params.RFS_WORKERS}'
export BYOS_MONITOR_RETRY_LIMIT='${params.MONITOR_RETRY_LIMIT}'
ENVEOF

                                        kubectl cp /tmp/byos-env.sh ma/migration-console-0:/tmp/byos-env.sh
                                        kubectl exec migration-console-0 -n ma -- bash -c '
                                            source /tmp/byos-env.sh && \
                                            cd /root/lib/integ_test && \
                                            pipenv run pytest integ_test/ma_workflow_test.py \
                                                --source_version=${env.sourceVer} \
                                                --target_version=${env.targetVer} \
                                                --test_ids=${testIdsResolved} \
                                                --reuse_clusters \
                                                -s
                                            '
                                    """
                                }
                            }
                        }
                    }
                }
            }
        }

        post {
            always {
                timeout(time: 75, unit: 'MINUTES') {
                    script {
                        if (env.clusterDetailsJson) {
                            def clusterDetails = readJSON text: env.clusterDetailsJson
                            def targetCluster = clusterDetails.target
                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: params.REGION, duration: 4500, roleSessionName: 'jenkins-session') {
                                    sh "kubectl -n ma get pods || true"
                                    sh "kubectl delete namespace ma --ignore-not-found --timeout=60s || true"
                                    // Remove added security group rule to allow proper cleanup of stacks
                                    sh """
                                      echo "Checking if target security group $targetCluster.securityGroupId exists..."

                                      if ! aws ec2 describe-security-groups --group-ids $targetCluster.securityGroupId >/dev/null 2>&1; then
                                        echo "Security group $targetCluster.securityGroupId does not exist. Skipping cleanup."
                                        exit 0
                                      fi

                                      echo "Checking for existing ingress rule to remove..."

                                      exists=\$(aws ec2 describe-security-groups \
                                        --group-ids $targetCluster.securityGroupId \
                                        --query "SecurityGroups[0].IpPermissions[?UserIdGroupPairs[?GroupId=='$env.clusterSecurityGroup']]" \
                                        --output json)

                                      if [ "\$exists" != "[]" ]; then
                                        echo "Ingress rule found. Revoking..."
                                        aws ec2 revoke-security-group-ingress \
                                          --group-id $targetCluster.securityGroupId \
                                          --protocol -1 \
                                          --port -1 \
                                          --source-group $env.clusterSecurityGroup
                                      else
                                        echo "No ingress rule to revoke."
                                      fi
                                    """
                                    sh "aws cloudformation delete-stack --stack-name Migration-Assistant-Infra-Import-VPC-eks-${env.STACK_NAME_SUFFIX} --region ${params.REGION}"
                                    sh "aws cloudformation wait stack-delete-complete --stack-name Migration-Assistant-Infra-Import-VPC-eks-${env.STACK_NAME_SUFFIX} --region ${params.REGION} || true"
                                    sh "cd $WORKSPACE/test/amazon-opensearch-service-sample-cdk && cdk destroy '*' --force --concurrency 3 && rm -f cdk.context.json"
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Deploy only the target cluster (no source cluster for BYOS scenario)
 */
def deployTargetClusterOnly(Map config) {
    def clusterContextValues = [
        stage: "${config.stage}",
        vpcAZCount: 2,
        clusters: [[
            clusterId: "target",
            clusterVersion: "${config.targetVer}",
            clusterType: "OPENSEARCH_MANAGED_SERVICE",
            openAccessPolicyEnabled: true,
            domainRemovalPolicy: "DESTROY",
            dataNodeType: config.sizeConfig.dataNodeType,
            dedicatedManagerNodeType: config.sizeConfig.dedicatedManagerNodeType,
            dataNodeCount: config.sizeConfig.dataNodeCount,
            dedicatedManagerNodeCount: config.sizeConfig.dedicatedManagerNodeCount,
            ebsEnabled: true,
            ebsVolumeSize: config.sizeConfig.ebsVolumeSize,
            nodeToNodeEncryption: true
        ]]
    ]

    def contextJsonStr = JsonOutput.prettyPrint(JsonOutput.toJson(clusterContextValues))
    writeFile(file: config.clusterContextFilePath, text: contextJsonStr)
    sh "echo 'Using cluster context file options:' && cat ${config.clusterContextFilePath}"
    withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
        withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: params.REGION, duration: 3600, roleSessionName: 'jenkins-session') {
            sh "./awsDeployCluster.sh --stage ${config.stage} --context-file ${config.clusterContextFilePath}"
        }
    }

    def rawJsonFile = readFile "tmp/cluster-details-${config.stage}.json"
    echo "Cluster details JSON:\n${rawJsonFile}"
    env.clusterDetailsJson = rawJsonFile
}
