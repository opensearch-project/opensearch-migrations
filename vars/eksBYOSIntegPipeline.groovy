/**
 * EKS BYOS (Bring Your Own Snapshot) Integration Pipeline
 * 
 * This pipeline tests migration from an existing S3 snapshot to a target OpenSearch cluster.
 * No source cluster is deployed - only a target cluster in Amazon OpenSearch Service.
 * Uses public Docker images (no custom image builds).
 * 
 * S3 bucket naming convention: migrations-jenkins-snapshot-{ACCOUNT_ID}-{REGION}
 * Account ID is derived dynamically from AWS credentials.
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
    
    // Target cluster size configurations
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
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/opensearch-project/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Git branch to use for repository')
            string(name: 'STAGE', defaultValue: "${defaultStageId}", description: 'Stage name for deployment environment')
            
            // Snapshot configuration - minimal parameters, bucket name derived from account ID
            string(name: 'SNAPSHOT_REGION', defaultValue: 'us-west-2', description: 'AWS region where snapshot bucket is located (also part of bucket name)')
            string(name: 'SNAPSHOT_FOLDER', defaultValue: 'large-snapshot-es6x', description: 'Folder name in the snapshot bucket')
            string(name: 'SNAPSHOT_NAME', defaultValue: 'large-snapshot', description: 'Name of the snapshot within the folder')
            choice(
                name: 'SOURCE_VERSION',
                choices: ['ES_5.6', 'ES_6.8', 'ES_7.10'],
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
            
            // Reuse options
            booleanParam(name: 'REUSE_TARGET_CLUSTER', defaultValue: true, description: 'Reuse existing target cluster (will clear indices instead of destroying)')
            booleanParam(name: 'SKIP_TARGET_CLEANUP', defaultValue: true, description: 'Skip target cluster cleanup after test (for debugging or reuse)')
        }

        options {
            lock(label: params.STAGE, quantity: 1, variable: 'maStageName')
            timeout(time: 3, unit: 'HOURS')
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
                when {
                    expression { return !params.REUSE_TARGET_CLUSTER }
                }
                steps {
                    timeout(time: 60, unit: 'MINUTES') {
                        dir('test') {
                            script {
                                def sizeConfig = targetClusterSizes[params.TARGET_CLUSTER_SIZE]
                                deployTargetClusterOnly(
                                    stage: "${maStageName}",
                                    clusterContextFilePath: "${clusterContextFilePath}",
                                    targetVer: params.TARGET_VERSION,
                                    sizeConfig: sizeConfig
                                )
                            }
                        }
                    }
                }
            }

            stage('Reuse Target Cluster') {
                when {
                    expression { return params.REUSE_TARGET_CLUSTER }
                }
                steps {
                    timeout(time: 10, unit: 'MINUTES') {
                        dir('test') {
                            script {
                                reuseTargetCluster(stage: "${maStageName}")
                            }
                        }
                    }
                }
            }

            stage('Deploy MA Stack') {
                steps {
                    timeout(time: 30, unit: 'MINUTES') {
                        dir('deployment/migration-assistant-solution') {
                            script {
                                env.STACK_NAME_SUFFIX = "${maStageName}-us-east-1"
                                def clusterDetails = readJSON text: env.clusterDetailsJson
                                def targetCluster = clusterDetails.target
                                def vpcId = targetCluster.vpcId
                                def subnetIds = "${targetCluster.subnetIds}"

                                sh "npm install"
                                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: "us-east-1", duration: 3600, roleSessionName: 'jenkins-session') {
                                        sh """
                                            cdk deploy Migration-Assistant-Infra-Import-VPC-eks-${env.STACK_NAME_SUFFIX} \
                                              --parameters Stage=${maStageName} \
                                              --parameters VPCId=${vpcId} \
                                              --parameters VPCSubnetIds=${subnetIds} \
                                              --require-approval never \
                                              --concurrency 3
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
                    timeout(time: 30, unit: 'MINUTES') {
                        script {
                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: "us-east-1", duration: 1200, roleSessionName: 'jenkins-session') {
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
                                        
                                        aws eks update-kubeconfig --region us-east-1 --name $env.eksClusterName

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
                                                    region: "us-east-1",
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

            stage('Install Helm Chart') {
                steps {
                    timeout(time: 15, unit: 'MINUTES') {
                        dir('deployment/k8s/aws') {
                            script {
                                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: "us-east-1", duration: 3600, roleSessionName: 'jenkins-session') {
                                        sh "./aws-bootstrap.sh --skip-git-pull --base-dir /home/ec2-user/workspace/${jobName} --use-public-images true --skip-console-exec --stage ${maStageName}"
                                    }
                                }
                            }
                        }
                    }
                }
            }

            stage('Run BYOS Migration Test') {
                steps {
                    timeout(time: 2, unit: 'HOURS') {
                        script {
                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: params.SNAPSHOT_REGION, duration: 3600, roleSessionName: 'jenkins-session') {
                                    // Derive bucket name from account ID and region
                                    // Convention: migrations-jenkins-snapshot-{ACCOUNT_ID}-{REGION}
                                    def snapshotBucket = "migrations-jenkins-snapshot-${MIGRATIONS_TEST_ACCOUNT_ID}-${params.SNAPSHOT_REGION}"
                                    def s3RepoUri = "s3://${snapshotBucket}/${params.SNAPSHOT_FOLDER}"
                                    
                                    // Run the BYOS test with env vars passed via file to avoid logging
                                    sh """
                                      cat > /tmp/byos-env.sh << 'ENVEOF'
export BYOS_SNAPSHOT_NAME='${params.SNAPSHOT_NAME}'
export BYOS_S3_REPO_URI='${s3RepoUri}'
export BYOS_S3_REGION='${params.SNAPSHOT_REGION}'
ENVEOF
                                      kubectl cp /tmp/byos-env.sh ma/migration-console-0:/tmp/byos-env.sh
                                      kubectl exec migration-console-0 -n ma -- bash -c '
                                        source /tmp/byos-env.sh && \
                                        cd /root/lib/integ_test && \
                                        pipenv run pytest integ_test/ma_workflow_test.py \
                                          --source_version=${params.SOURCE_VERSION} \
                                          --target_version=${params.TARGET_VERSION} \
                                          --test_ids=0010 \
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

            stage('Clear Target Indices') {
                when {
                    expression { return params.REUSE_TARGET_CLUSTER || params.SKIP_TARGET_CLEANUP }
                }
                steps {
                    timeout(time: 5, unit: 'MINUTES') {
                        script {
                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: "us-east-1", duration: 600, roleSessionName: 'jenkins-session') {
                                    sh """
                                      kubectl exec migration-console-0 -n ma -- bash -c '
                                        source /.venv/bin/activate && \
                                        console clusters clear-indices --cluster target --acknowledge-risk
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
                        if (env.eksClusterName) {
                            def clusterDetails = readJSON text: env.clusterDetailsJson
                            def targetCluster = clusterDetails.target
                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: "us-east-1", duration: 4500, roleSessionName: 'jenkins-session') {
                                    sh "kubectl -n ma get pods || true"
                                    
                                    // Cleanup MA namespace
                                    sh """
                                      helm uninstall ma -n ma --wait --timeout 300s || true
                                      kubectl delete namespace ma --ignore-not-found --timeout=60s || true
                                    """
                                    
                                    // Remove security group rule
                                    sh """
                                      if aws ec2 describe-security-groups --group-ids $targetCluster.securityGroupId >/dev/null 2>&1; then
                                        exists=\$(aws ec2 describe-security-groups \
                                          --group-ids $targetCluster.securityGroupId \
                                          --query "SecurityGroups[0].IpPermissions[?UserIdGroupPairs[?GroupId=='$env.clusterSecurityGroup']]" \
                                          --output json)
                                      
                                        if [ "\$exists" != "[]" ]; then
                                          aws ec2 revoke-security-group-ingress \
                                            --group-id $targetCluster.securityGroupId \
                                            --protocol -1 \
                                            --port -1 \
                                            --source-group $env.clusterSecurityGroup || true
                                        fi
                                      fi
                                    """
                                    
                                    // Destroy MA CDK stack
                                    sh "cd $WORKSPACE/deployment/migration-assistant-solution && cdk destroy Migration-Assistant-Infra-Import-VPC-eks-${env.STACK_NAME_SUFFIX} --force --concurrency 3 || true"
                                    
                                    // Conditionally destroy target cluster
                                    if (!params.SKIP_TARGET_CLEANUP && !params.REUSE_TARGET_CLUSTER) {
                                        sh "cd $WORKSPACE/test/amazon-opensearch-service-sample-cdk && cdk destroy '*' --force --concurrency 3 && rm -f cdk.context.json || true"
                                    } else {
                                        echo "Skipping target cluster cleanup (REUSE_TARGET_CLUSTER=${params.REUSE_TARGET_CLUSTER}, SKIP_TARGET_CLEANUP=${params.SKIP_TARGET_CLEANUP})"
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
        withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: "us-east-1", duration: 3600, roleSessionName: 'jenkins-session') {
            sh "./awsDeployCluster.sh --stage ${config.stage} --context-file ${config.clusterContextFilePath}"
        }
    }

    def rawJsonFile = readFile "tmp/cluster-details-${config.stage}.json"
    echo "Cluster details JSON:\n${rawJsonFile}"
    env.clusterDetailsJson = rawJsonFile
}

/**
 * Reuse existing target cluster - retrieve details from existing CloudFormation stack
 */
def reuseTargetCluster(Map config) {
    withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
        withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: "us-east-1", duration: 1200, roleSessionName: 'jenkins-session') {
            // Find existing target cluster stack
            def stacks = sh(
                script: """
                  aws cloudformation list-stacks \
                    --query "StackSummaries[?StackStatus!='DELETE_COMPLETE' && StackStatus!='DELETE_IN_PROGRESS' && contains(StackName, 'OpenSearchDomain-target-${config.stage}')].StackName" \
                    --output text
                """,
                returnStdout: true
            ).trim()

            if (!stacks) {
                error("No existing target cluster found for stage ${config.stage}. Cannot reuse.")
            }

            def stackName = stacks.split('\n')[0].trim()
            echo "Found existing target cluster stack: ${stackName}"

            // Get cluster details
            def outputs = sh(
                script: """
                  aws cloudformation describe-stacks --stack-name ${stackName} \
                    --query "Stacks[0].Outputs" --output json
                """,
                returnStdout: true
            ).trim()

            def outputsJson = readJSON text: outputs
            def endpoint = "https://" + outputsJson.find { it.OutputKey =~ /^ClusterEndpoint/ }?.OutputValue
            def securityGroupId = outputsJson.find { it.OutputKey =~ /^ClusterAccessSecurityGroupId/ }?.OutputValue
            def subnets = outputsJson.find { it.OutputKey =~ /^ClusterSubnets/ }?.OutputValue

            // Get VPC ID from network stack
            def networkStack = sh(
                script: """
                  aws cloudformation list-stacks \
                    --query "StackSummaries[?StackStatus!='DELETE_COMPLETE' && contains(StackName, 'NetworkInfra-${config.stage}')].StackName" \
                    --output text | head -1
                """,
                returnStdout: true
            ).trim()

            def vpcId = sh(
                script: """
                  aws cloudformation describe-stacks --stack-name ${networkStack} \
                    --query "Stacks[0].Outputs[?contains(OutputValue, 'vpc')].OutputValue" \
                    --output text
                """,
                returnStdout: true
            ).trim()

            def clusterDetails = [
                target: [
                    vpcId: vpcId,
                    endpoint: endpoint,
                    securityGroupId: securityGroupId,
                    subnetIds: subnets
                ]
            ]

            env.clusterDetailsJson = JsonOutput.toJson(clusterDetails)
            echo "Reusing cluster details:\n${env.clusterDetailsJson}"
        }
    }
}
