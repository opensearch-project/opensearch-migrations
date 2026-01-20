/**
 * EKS BYOS (Bring Your Own Snapshot) Integration Pipeline
 * 
 * This pipeline tests migration from an existing S3 snapshot to a target OpenSearch cluster.
 * No source cluster is deployed - only a target cluster in Amazon OpenSearch Service.
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
            dedicatedMasterEnabled: false,
            masterNodeCount: 0,
            ebsVolumeSize: 100
        ],
        'large': [
            dataNodeType: "r6g.4xlarge.search",
            dedicatedManagerNodeType: "m6g.xlarge.search",
            dataNodeCount: 6,
            dedicatedMasterEnabled: true,
            masterNodeCount: 4,
            ebsVolumeSize: 1024
        ]
    ]
    pipeline {
        agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }

        parameters {
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/jugal-chauhan/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: 'jenkins-pipeline-eks-large-migration', description: 'Git branch to use for repository')
            string(name: 'STAGE', defaultValue: "${defaultStageId}", description: 'Stage name for deployment environment')
            string(name: 'RFS_WORKERS', defaultValue: '1', description: 'Number of RFS worker pods for document backfill (podReplicas)')

            // Snapshot configuration
            string(name: 'S3_REPO_URI', defaultValue: 's3://migrations-snapshots-library-us-east-1/large-snapshot-es5x/', description: 'Full S3 URI to snapshot repository (e.g., s3://bucket/folder/)')
            string(name: 'REGION', defaultValue: 'us-east-1', description: 'AWS region for deployment and snapshot bucket')
            string(name: 'SNAPSHOT_NAME', defaultValue: 'large-snapshot', description: 'Name of the snapshot')
            string(name: 'TEST_IDS', defaultValue: '0010', description: 'Test IDs to execute (comma separated, e.g., "0010" or "0010,0011")')
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
                description: 'Target cluster size (default: 2x r6g.large, large: 6x r6g.4xlarge with dedicated masters)'
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
            stage('Print Configuration') {
                steps {
                    script {
                        echo """
================================================================================
BYOS Migration Pipeline Configuration
================================================================================
Git Configuration:
  Repository:        ${params.GIT_REPO_URL}
  Branch:            ${params.GIT_BRANCH}
  Stage:             ${params.STAGE}

Snapshot Configuration:
  S3 URI:            ${params.S3_REPO_URI}
  Region:            ${params.REGION}
  Snapshot Name:     ${params.SNAPSHOT_NAME}
  Source Version:    ${params.SOURCE_VERSION}

Target Cluster Configuration:
  Target Version:    ${params.TARGET_VERSION}
  Cluster Size:      ${params.TARGET_CLUSTER_SIZE}

Migration Configuration:
  RFS Workers:       ${params.RFS_WORKERS}
  Test IDs:          ${params.TEST_IDS}
================================================================================
"""
                    }
                }
            }

            stage('Checkout') {
                steps {
                    checkoutStep(branch: params.GIT_BRANCH, repo: params.GIT_REPO_URL)
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

            stage('Deploy Target Cluster') {
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

            stage('Synth MA Stack') {
                steps {
                    timeout(time: 20, unit: 'MINUTES') {
                        dir('deployment/migration-assistant-solution') {
                            script {
                                echo "Synthesizing CloudFormation templates via CDK..."
                                sh "npm install --dev"
                                sh "npx cdk synth '*'"
                                echo "CDK synthesis completed. EKS CFN Templates should be available in cdk.out/"
                            }
                        }
                    }
                }
            }

            stage('Deploy EKS CFN Stack') {
                steps {
                    timeout(time: 30, unit: 'MINUTES') {
                        dir('deployment/migration-assistant-solution') {
                            script {
                                env.STACK_NAME_SUFFIX = "${maStageName}-${params.REGION}"
                                env.MA_STACK_NAME = "Migration-Assistant-Infra-Import-VPC-eks-${env.STACK_NAME_SUFFIX}"
                                def clusterDetails = readJSON text: env.clusterDetailsJson
                                def targetCluster = clusterDetails.target
                                def vpcId = targetCluster.vpcId
                                def subnetIds = "${targetCluster.subnetIds}"

                                echo "Deploying CloudFormation stack: ${env.MA_STACK_NAME} in region ${params.REGION}"
                                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: params.REGION, duration: 3600, roleSessionName: 'jenkins-session') {
                                        sh """
                                            set -euo pipefail
                                            aws cloudformation create-stack \
                                              --stack-name "${env.MA_STACK_NAME}" \
                                              --template-body file://cdk.out/Migration-Assistant-Infra-Import-VPC-eks.template.json \
                                              --parameters ParameterKey=Stage,ParameterValue=${maStageName} \
                                                           ParameterKey=VPCId,ParameterValue=${vpcId} \
                                                           ParameterKey=VPCSubnetIds,ParameterValue=\\"${subnetIds}\\" \
                                              --capabilities CAPABILITY_IAM CAPABILITY_NAMED_IAM \
                                              --region "${params.REGION}"

                                            echo "Waiting for stack CREATE_COMPLETE..."
                                            aws cloudformation wait stack-create-complete \
                                              --stack-name "${env.MA_STACK_NAME}" \
                                              --region "${params.REGION}"

                                            echo "CloudFormation stack ${env.MA_STACK_NAME} is CREATE_COMPLETE."
                                        """
                                    }
                                }
                            }
                        }
                    }
                }
            }

            stage('Configure EKS') {
                steps {
                    timeout(time: 10, unit: 'MINUTES') {
                        script {
                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: params.REGION, duration: 1200, roleSessionName: 'jenkins-session') {
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
                                        error("Could not retrieve CloudFormation Output 'MigrationsExportString'")
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

                                    def principalArn = 'arn:aws:iam::$MIGRATIONS_TEST_ACCOUNT_ID:role/JenkinsDeploymentRole'
                                    sh """
                                        if aws eks describe-access-entry --cluster-name $env.eksClusterName --principal-arn $principalArn >/dev/null 2>&1; then
                                          echo "Access entry already exists, skipping create."
                                        else
                                          aws eks create-access-entry --cluster-name $env.eksClusterName --principal-arn $principalArn --type STANDARD
                                        fi
                                        
                                        aws eks associate-access-policy \
                                          --cluster-name $env.eksClusterName \
                                          --principal-arn $principalArn \
                                          --policy-arn arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy \
                                          --access-scope type=cluster

                                        aws eks update-kubeconfig --region ${params.REGION} --name $env.eksClusterName

                                        for i in {1..10}; do
                                          if kubectl get namespace default >/dev/null 2>&1; then
                                            echo "kubectl is ready for use"
                                            break
                                          fi
                                          echo "Waiting for kubectl to be ready... (\$i/10)"
                                          sleep 5
                                        done
                                    """

                                    sh 'kubectl create namespace ma --dry-run=client -o yaml | kubectl apply -f -'

                                    def clusterDetails = readJSON text: env.clusterDetailsJson
                                    def targetCluster = clusterDetails.target
                                    def targetVersionExpanded = expandVersionString("${params.TARGET_VERSION}")

                                    // Target configmap only (no source cluster)
                                    writeJSON file: '/tmp/target-cluster-config.json', json: [
                                            endpoint: targetCluster.endpoint,
                                            allow_insecure: true,
                                            sigv4: [
                                                    region: params.REGION,
                                                    service: "es"
                                            ],
                                            version: params.TARGET_VERSION
                                    ]
                                    sh """
                                      kubectl create configmap target-${targetVersionExpanded}-migration-config \
                                        --from-file=cluster-config=/tmp/target-cluster-config.json \
                                        --namespace ma --dry-run=client -o yaml | kubectl apply -f -
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

            stage('Build Docker Images') {
                steps {
                    timeout(time: 1, unit: 'HOURS') {
                        script {
                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: params.REGION, duration: 3600, roleSessionName: 'jenkins-session') {
                                    // Install QEMU for cross-architecture builds (arm64 on x86_64 host)
                                    sh "docker run --privileged --rm tonistiigi/binfmt --install all"

                                    def builderExists = sh(
                                        script: "docker buildx ls | grep -q '^ecr-builder'",
                                        returnStatus: true
                                    ) == 0

                                    if (builderExists) {
                                        echo "Removing existing buildx builder 'ecr-builder'"
                                        sh "docker buildx rm ecr-builder"
                                    }
                                    
                                    echo "Creating new buildx builder 'ecr-builder'"
                                    sh "docker buildx create --name ecr-builder --driver docker-container --bootstrap"
                                    sh "docker buildx use ecr-builder"
                                    sh "./gradlew buildImagesToRegistry -PregistryEndpoint=${env.registryEndpoint} -Pbuilder=ecr-builder"
                                }
                            }
                        }
                    }
                }
            }

            stage('Install Helm Chart') {
                steps {
                    timeout(time: 10, unit: 'MINUTES') {
                        dir('deployment/k8s/aws') {
                            script {
                                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: params.REGION, duration: 3600, roleSessionName: 'jenkins-session') {
                                        sh "./aws-bootstrap.sh --skip-git-pull --base-dir ${WORKSPACE} --use-public-images false --skip-console-exec --stage ${maStageName}"
                                    }
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
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: params.REGION, duration: 3600, roleSessionName: 'jenkins-session') {
                                    // Wait for migration-console pod to be ready
                                    sh """
                                      echo "Waiting for migration-console pod to be ready..."
                                      kubectl wait --for=condition=Ready pod/migration-console-0 -n ma --timeout=600s
                                      echo "migration-console pod is ready"
                                    """

                                    sh """
                                      echo "Applying workflow template..."
                                      kubectl apply -f ${WORKSPACE}/migrationConsole/lib/integ_test/testWorkflows/fullMigrationImportedClusters.yaml -n ma
                                      echo "Workflow template applied"
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
                timeout(time: 90, unit: 'MINUTES') {
                    script {
                        def region = params.REGION
                        def networkStackName = "NetworkInfra-${maStageName}-${region}"
                        def domainStackName = "OpenSearchDomain-target-${maStageName}-${region}"
                        def maStackName = "Migration-Assistant-Infra-Import-VPC-eks-${maStageName}-${region}"

                        withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                            withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: region, duration: 4500, roleSessionName: 'jenkins-session') {
                                // Stage 1: Cleanup MA resources
                                stage('Cleanup MA Resources') {
                                    sh "kubectl -n ma get pods || true"
                                    sh """
                                      helm uninstall ma -n ma --wait --timeout 300s || true
                                      kubectl delete namespace ma --ignore-not-found --timeout=60s || true
                                    """

                                    if (env.clusterDetailsJson && env.clusterSecurityGroup) {
                                        def clusterDetails = readJSON text: env.clusterDetailsJson
                                        def targetCluster = clusterDetails.target
                                        sh """
                                          if aws ec2 describe-security-groups --group-ids ${targetCluster.securityGroupId} >/dev/null 2>&1; then
                                            exists=\$(aws ec2 describe-security-groups \
                                              --group-ids ${targetCluster.securityGroupId} \
                                              --query "SecurityGroups[0].IpPermissions[?UserIdGroupPairs[?GroupId=='${env.clusterSecurityGroup}']]" \
                                              --output json)
                                            if [ "\$exists" != "[]" ]; then
                                              aws ec2 revoke-security-group-ingress \
                                                --group-id ${targetCluster.securityGroupId} \
                                                --protocol -1 --port -1 \
                                                --source-group ${env.clusterSecurityGroup} || true
                                            fi
                                          fi
                                        """
                                    }

                                    sh """
                                      set -euo pipefail
                                      echo "Deleting stack: ${maStackName}"
                                      aws cloudformation delete-stack --stack-name ${maStackName} || true
                                    """
                                    sh """
                                      set -euo pipefail
                                      echo "Waiting for stack deletion: ${maStackName}"
                                      deadline=\$((SECONDS + 1800))  # 30 minute timeout
                                      while [ \$SECONDS -lt \$deadline ]; do
                                        status=\$(aws cloudformation describe-stacks --stack-name ${maStackName} \
                                          --query 'Stacks[0].StackStatus' --output text 2>/dev/null || echo "DELETED")
                                        echo "Stack status: \$status"
                                        if [ "\$status" = "DELETED" ] || [ "\$status" = "DELETE_COMPLETE" ]; then
                                          echo "Stack deleted successfully"
                                          exit 0
                                        elif [ "\$status" = "DELETE_FAILED" ]; then
                                          echo "Stack deletion failed"
                                          exit 1
                                        fi
                                        sleep 30
                                      done
                                      echo "Timeout waiting for stack deletion"
                                      exit 1
                                    """
                                }

                                // Stage 2: Destroy OpenSearch domain stack
                                stage('Destroy OpenSearch Domain') {
                                    sh """
                                      set -euo pipefail
                                      echo "Deleting stack: ${domainStackName}"
                                      aws cloudformation delete-stack --stack-name ${domainStackName} || true
                                    """
                                    sh """
                                      set -euo pipefail
                                      echo "Waiting for stack deletion: ${domainStackName}"
                                      deadline=\$((SECONDS + 5400))  # 90 minute timeout
                                      while [ \$SECONDS -lt \$deadline ]; do
                                        status=\$(aws cloudformation describe-stacks --stack-name ${domainStackName} \
                                          --query 'Stacks[0].StackStatus' --output text 2>/dev/null || echo "DELETED")
                                        echo "Stack status: \$status"
                                        if [ "\$status" = "DELETED" ] || [ "\$status" = "DELETE_COMPLETE" ]; then
                                          echo "Stack deleted successfully"
                                          exit 0
                                        elif [ "\$status" = "DELETE_FAILED" ]; then
                                          echo "Stack deletion failed"
                                          exit 1
                                        fi
                                        sleep 30
                                      done
                                      echo "Timeout waiting for stack deletion"
                                      exit 1
                                    """
                                }

                                // Stage 3: Wait for VPC dependencies to drain
                                stage('Wait for VPC Dependencies') {
                                    sh """
                                      set -euo pipefail
                                      VPC_ID=\$(aws cloudformation describe-stacks --stack-name ${networkStackName} \
                                        --query 'Stacks[0].Outputs[?OutputKey==`VpcIdExport${maStageName}`].OutputValue' --output text)
                                      echo "VPC_ID=\$VPC_ID"

                                      deadline=\$((SECONDS + 600))
                                      while [ \$SECONDS -lt \$deadline ]; do
                                        vpce_count=\$(aws ec2 describe-vpc-endpoints \
                                          --filters Name=vpc-id,Values="\$VPC_ID" \
                                          --query 'length(VpcEndpoints)' --output text 2>/dev/null || echo 0)
                                        lb_count=\$(aws elbv2 describe-load-balancers \
                                          --query "length(LoadBalancers[?VpcId=='\$VPC_ID'])" --output text 2>/dev/null || echo 0)
                                        external_eni_count=\$(aws ec2 describe-network-interfaces \
                                          --filters Name=vpc-id,Values="\$VPC_ID" \
                                          --query 'length(NetworkInterfaces[?InterfaceType!=`nat_gateway` && InterfaceType!=`vpc_endpoint` && InterfaceType!=`gateway_load_balancer` && InterfaceType!=`gateway_load_balancer_endpoint`])' \
                                          --output text 2>/dev/null || echo 0)

                                        echo "Waiting for external VPC dependencies... VPCEs=\$vpce_count LBs=\$lb_count External_ENIs=\$external_eni_count"

                                        if [ "\$vpce_count" = "0" ] && [ "\$lb_count" = "0" ] && [ "\$external_eni_count" = "0" ]; then
                                          echo "External VPC dependencies drained."
                                          exit 0
                                        fi
                                        sleep 30
                                      done

                                      echo "Timed out waiting for external VPC dependencies. Dumping remaining:"
                                      aws ec2 describe-vpc-endpoints --filters Name=vpc-id,Values="\$VPC_ID" --output table || true
                                      aws elbv2 describe-load-balancers --query "LoadBalancers[?VpcId=='\$VPC_ID']" --output table || true
                                      aws ec2 describe-network-interfaces --filters Name=vpc-id,Values="\$VPC_ID" \
                                        --query 'NetworkInterfaces[*].{Id:NetworkInterfaceId,Type:InterfaceType,Status:Status,Desc:Description}' --output table || true
                                      exit 1
                                    """
                                }

                                // Stage 4: Destroy network stack
                                stage('Destroy Network Stack') {
                                    sh """
                                      set -euo pipefail
                                      echo "Deleting stack: ${networkStackName}"
                                      aws cloudformation delete-stack --stack-name ${networkStackName} || true
                                    """
                                    sh """
                                      set -euo pipefail
                                      echo "Waiting for stack deletion: ${networkStackName}"
                                      deadline=\$((SECONDS + 1800))  # 30 minute timeout
                                      while [ \$SECONDS -lt \$deadline ]; do
                                        status=\$(aws cloudformation describe-stacks --stack-name ${networkStackName} \
                                          --query 'Stacks[0].StackStatus' --output text 2>/dev/null || echo "DELETED")
                                        echo "Stack status: \$status"
                                        if [ "\$status" = "DELETED" ] || [ "\$status" = "DELETE_COMPLETE" ]; then
                                          echo "Stack deleted successfully"
                                          exit 0
                                        elif [ "\$status" = "DELETE_FAILED" ]; then
                                          echo "Stack deletion failed"
                                          exit 1
                                        fi
                                        sleep 30
                                      done
                                      echo "Timeout waiting for stack deletion"
                                      exit 1
                                    """
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
            dedicatedMasterEnabled: config.sizeConfig.dedicatedMasterEnabled,
            masterNodeCount: config.sizeConfig.masterNodeCount,
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
