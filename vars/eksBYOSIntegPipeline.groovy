/**
 * EKS BYOS (Bring Your Own Snapshot) Integration Pipeline
 * 
 * This pipeline tests migration from an existing S3 snapshot to a target OpenSearch cluster.
 * No source cluster is deployed - only a target cluster in Amazon OpenSearch Service.
 * 
 * Required parameters:
 * - SNAPSHOT_BUCKET: S3 bucket containing the snapshot
 * - SNAPSHOT_FOLDER: Folder/base_path within the bucket
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
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/jugal-chauhan/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: 'jenkins-pipeline-eks-large-migration', description: 'Git branch to use for repository')
            string(name: 'STAGE', defaultValue: "${defaultStageId}", description: 'Stage name for deployment environment')
            
            // Snapshot configuration
            string(name: 'SNAPSHOT_BUCKET', defaultValue: 'migrations-snapshots-library-us-west-2', description: 'S3 bucket containing the snapshot')
            string(name: 'SNAPSHOT_REGION', defaultValue: 'us-west-2', description: 'AWS region where snapshot bucket is located')
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
            
            // Build options
            booleanParam(name: 'BUILD_IMAGES', defaultValue: false, description: 'Build container images from source instead of using public images')
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

            stage('Deploy MA Stack') {
                steps {
                    timeout(time: 30, unit: 'MINUTES') {
                        dir('deployment/migration-assistant-solution') {
                            script {
                                env.STACK_NAME_SUFFIX = "${maStageName}-${params.SNAPSHOT_REGION}"
                                def clusterDetails = readJSON text: env.clusterDetailsJson
                                def targetCluster = clusterDetails.target
                                def vpcId = targetCluster.vpcId
                                def subnetIds = "${targetCluster.subnetIds}"

                                sh "npm install"
                                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: params.SNAPSHOT_REGION, duration: 3600, roleSessionName: 'jenkins-session') {
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
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: params.SNAPSHOT_REGION, duration: 1200, roleSessionName: 'jenkins-session') {
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
                                        
                                        aws eks update-kubeconfig --region ${params.SNAPSHOT_REGION} --name $env.eksClusterName

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
                                                    region: params.SNAPSHOT_REGION,
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
                when {
                    expression { return params.BUILD_IMAGES }
                }
                steps {
                    timeout(time: 1, unit: 'HOURS') {
                        script {
                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: params.SNAPSHOT_REGION, duration: 3600, roleSessionName: 'jenkins-session') {
                                    sh "docker run --privileged --rm tonistiigi/binfmt --install all"

                                    // Remove and recreate builder to ensure fresh credentials are used
                                    sh "docker buildx rm ecr-builder || true"
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
                    timeout(time: 15, unit: 'MINUTES') {
                        dir('deployment/k8s/aws') {
                            script {
                                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: params.SNAPSHOT_REGION, duration: 3600, roleSessionName: 'jenkins-session') {
                                        def usePublicImages = params.BUILD_IMAGES ? "false" : "true"
                                        sh "./aws-bootstrap.sh --skip-git-pull --base-dir ${WORKSPACE} --use-public-images ${usePublicImages} --skip-console-exec --stage ${maStageName}"
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
                                    // Wait for migration-console pod to be ready
                                    sh """
                                      echo "Waiting for migration-console pod to be ready..."
                                      kubectl wait --for=condition=Ready pod/migration-console-0 -n ma --timeout=600s
                                      echo "migration-console pod is ready"
                                    """
                                    
                                    // Copy test files when using public images (they don't have our branch code)
                                    if (!params.BUILD_IMAGES) {
                                        sh """
                                          echo "Copying test files to migration-console (using public images)..."
                                          kubectl cp ${WORKSPACE}/migrationConsole/lib/integ_test/integ_test/test_cases/snapshot_only_tests.py \
                                            ma/migration-console-0:/root/lib/integ_test/integ_test/test_cases/snapshot_only_tests.py
                                          kubectl cp ${WORKSPACE}/migrationConsole/lib/integ_test/integ_test/conftest.py \
                                            ma/migration-console-0:/root/lib/integ_test/integ_test/conftest.py
                                          echo "Test files copied"
                                        """
                                    }
                                    
                                    // Apply the workflow template to the cluster
                                    sh """
                                      echo "Applying workflow template..."
                                      kubectl apply -f ${WORKSPACE}/migrationConsole/lib/integ_test/testWorkflows/fullMigrationImportedClusters.yaml -n ma
                                      echo "Workflow template applied"
                                    """
                                    
                                    def s3RepoUri = "s3://${params.SNAPSHOT_BUCKET}/${params.SNAPSHOT_FOLDER}"
                                    
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
        }

        post {
            always {
                timeout(time: 75, unit: 'MINUTES') {
                    script {
                        if (env.eksClusterName) {
                            def clusterDetails = readJSON text: env.clusterDetailsJson
                            def targetCluster = clusterDetails.target
                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: params.SNAPSHOT_REGION, duration: 4500, roleSessionName: 'jenkins-session') {
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
                                    
                                    // Destroy target cluster
                                    sh "cd $WORKSPACE/test/amazon-opensearch-service-sample-cdk && cdk destroy '*' --force --concurrency 3 && rm -f cdk.context.json || true"
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
        withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: params.SNAPSHOT_REGION, duration: 3600, roleSessionName: 'jenkins-session') {
            sh "./awsDeployCluster.sh --stage ${config.stage} --context-file ${config.clusterContextFilePath}"
        }
    }

    def rawJsonFile = readFile "tmp/cluster-details-${config.stage}.json"
    echo "Cluster details JSON:\n${rawJsonFile}"
    env.clusterDetailsJson = rawJsonFile
}
