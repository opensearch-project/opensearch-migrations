def call(Map config = [:]) {
    def collectionType = config.collectionType ?: 'SEARCH'
    def testIdMap = ['SEARCH': '0021', 'TIMESERIES': '0022', 'VECTORSEARCH': '0023']
    def envVarMap = ['SEARCH': 'AOSS_SEARCH_ENDPOINT', 'TIMESERIES': 'AOSS_TIMESERIES_ENDPOINT', 'VECTORSEARCH': 'AOSS_VECTOR_ENDPOINT']
    def testId = testIdMap[collectionType]
    def endpointEnvVar = envVarMap[collectionType]
    def defaultStageId = config.defaultStageId ?: "eks-aoss-${collectionType.toLowerCase()}"
    def jobName = config.jobName ?: "eks-aoss-${collectionType.toLowerCase()}-integ-test"
    def clusterContextFilePath = "tmp/cluster-context-aoss-${currentBuild.number}.json"

    pipeline {
        agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }

        parameters {
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/opensearch-project/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Git branch to use for repository')
            string(name: 'STAGE', defaultValue: "${defaultStageId}", description: 'Stage name for deployment environment')
            string(name: 'REGION', defaultValue: 'us-east-1', description: 'AWS region for deployment')
            choice(name: 'SOURCE_VERSION', choices: ['ES_7.10'], description: 'Version of the source cluster')
            string(name: 'RFS_WORKERS', defaultValue: '1', description: 'Number of RFS worker pods for document backfill')
            booleanParam(name: 'SKIP_DELETE', defaultValue: false, description: 'Skip deletion of all resources after test (for debugging)')
            booleanParam(name: 'REUSE_EXISTING', defaultValue: false, description: 'Reuse existing source, target, and MA resources')
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
            stage('Checkout & Print Params') {
                steps {
                    checkoutStep(branch: params.GIT_BRANCH, repo: params.GIT_REPO_URL)
                    script {
                        // Set env vars used across stages
                        env.AOSS_COLLECTION_NAME = "ma-${maStageName}"
                        env.STACK_NAME = "MA-Serverless-${maStageName}-${params.REGION}"
                        env.eksClusterName = "migration-eks-cluster-${maStageName}-${params.REGION}"
                        env.eksKubeContext = env.eksClusterName
                        env.SOURCE_DOMAIN_STACK = "OpenSearchDomain-source-${maStageName}-${params.REGION}"
                        env.NETWORK_STACK = "NetworkInfra-${maStageName}-${params.REGION}"

                        echo """
                            ================================================================
                            AOSS ${collectionType} Collection Integration Test
                            ================================================================
                            Git:              ${params.GIT_REPO_URL} @ ${params.GIT_BRANCH}
                            Stage:            ${maStageName}
                            Region:           ${params.REGION}
                            Collection Type:  ${collectionType}
                            Test ID:          ${testId}
                            Source:           ${params.SOURCE_VERSION}
                            Workers:          ${params.RFS_WORKERS}
                            Skip Delete:      ${params.SKIP_DELETE}
                            Reuse Existing:   ${params.REUSE_EXISTING}
                            ================================================================
                        """
                    }
                }
            }

            stage('Discover Existing Resources') {
                when { expression { params.REUSE_EXISTING } }
                steps {
                    timeout(time: 5, unit: 'MINUTES') {
                        script {
                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: params.REGION, duration: 3600, roleSessionName: 'jenkins-session') {
                                    def collName = env.AOSS_COLLECTION_NAME

                                    // Discover AOSS collection
                                    env.AOSS_COLLECTION_ENDPOINT = sh(
                                        script: "aws opensearchserverless batch-get-collection --names '${collName}' --query 'collectionDetails[0].collectionEndpoint' --output text",
                                        returnStdout: true
                                    ).trim()
                                    env.AOSS_COLLECTION_ID = sh(
                                        script: "aws opensearchserverless batch-get-collection --names '${collName}' --query 'collectionDetails[0].id' --output text",
                                        returnStdout: true
                                    ).trim()
                                    echo "Discovered AOSS: endpoint=${env.AOSS_COLLECTION_ENDPOINT} id=${env.AOSS_COLLECTION_ID}"

                                    // Discover source cluster
                                    def sourceDetails = sh(
                                        script: """aws cloudformation describe-stacks --stack-name "${env.SOURCE_DOMAIN_STACK}" \
                                          --query 'Stacks[0].Outputs' --output json""",
                                        returnStdout: true
                                    ).trim()
                                    env.SOURCE_ENDPOINT = "https://" + sh(
                                        script: "echo '${sourceDetails}' | jq -r '.[] | select(.OutputKey | test(\"ClusterEndpoint\")) | .OutputValue'",
                                        returnStdout: true
                                    ).trim()
                                    echo "Discovered source: ${env.SOURCE_ENDPOINT}"

                                    // Discover EKS
                                    sh "aws eks update-kubeconfig --region ${params.REGION} --name ${env.eksClusterName} --alias ${env.eksClusterName}"
                                    echo "Discovered EKS cluster: ${env.eksClusterName}"
                                }
                            }
                        }
                    }
                }
            }

            stage('Deploy ES 7.10 Source') {
                when { expression { !params.REUSE_EXISTING } }
                steps {
                    timeout(time: 60, unit: 'MINUTES') {
                        dir('test') {
                            script {
                                deployClustersStep(
                                    stage: "${maStageName}",
                                    clusterContextFilePath: "${clusterContextFilePath}",
                                    sourceVer: params.SOURCE_VERSION,
                                    sourceClusterType: "OPENSEARCH_MANAGED_SERVICE",
                                    region: params.REGION
                                )
                                def clusterDetails = readJSON text: env.clusterDetailsJson
                                env.SOURCE_ENDPOINT = clusterDetails.source.endpoint
                                env.SOURCE_VPC_ID = clusterDetails.source.vpcId
                                env.SOURCE_SUBNET_IDS = clusterDetails.source.subnetIds
                                echo "Source cluster deployed: ${env.SOURCE_ENDPOINT}"
                            }
                        }
                    }
                }
            }

            stage('Create AOSS Collection') {
                when { expression { !params.REUSE_EXISTING } }
                steps {
                    timeout(time: 15, unit: 'MINUTES') {
                        script {
                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: params.REGION, duration: 3600, roleSessionName: 'jenkins-session') {
                                    def jenkinsRoleArn = "arn:aws:iam::${MIGRATIONS_TEST_ACCOUNT_ID}:role/JenkinsDeploymentRole"
                                    def collName = env.AOSS_COLLECTION_NAME

                                    sh """
                                        aws opensearchserverless create-security-policy \
                                          --name "${collName}-enc" \
                                          --type encryption \
                                          --policy '{"Rules":[{"ResourceType":"collection","Resource":["collection/${collName}"]}],"AWSOwnedKey":true}'

                                        aws opensearchserverless create-security-policy \
                                          --name "${collName}-net" \
                                          --type network \
                                          --policy '[{"Rules":[{"ResourceType":"collection","Resource":["collection/${collName}"]},{"ResourceType":"dashboard","Resource":["collection/${collName}"]}],"AllowFromPublic":true}]'

                                        aws opensearchserverless create-collection \
                                          --name "${collName}" \
                                          --type ${collectionType}

                                        echo "Waiting for AOSS collection to become ACTIVE..."
                                        for i in \$(seq 1 60); do
                                            status=\$(aws opensearchserverless batch-get-collection \
                                              --names "${collName}" \
                                              --query 'collectionDetails[0].status' --output text)
                                            echo "  Attempt \$i: status=\$status"
                                            [ "\$status" = "ACTIVE" ] && break
                                            [ "\$status" = "FAILED" ] && { echo "AOSS collection creation FAILED"; exit 1; }
                                            sleep 15
                                        done
                                        [ "\$status" = "ACTIVE" ] || { echo "Timed out waiting for AOSS collection"; exit 1; }
                                    """

                                    def accessPolicy = """[{"Rules":[{"ResourceType":"index","Resource":["index/${collName}/*"],"Permission":["aoss:*"]},{"ResourceType":"collection","Resource":["collection/${collName}"],"Permission":["aoss:*"]}],"Principal":["${jenkinsRoleArn}"]}]"""
                                    sh """aws opensearchserverless create-access-policy \
                                          --name "${collName}-access" \
                                          --type data \
                                          --policy '${accessPolicy}'"""

                                    env.AOSS_COLLECTION_ENDPOINT = sh(
                                        script: "aws opensearchserverless batch-get-collection --names '${collName}' --query 'collectionDetails[0].collectionEndpoint' --output text",
                                        returnStdout: true
                                    ).trim()
                                    env.AOSS_COLLECTION_ID = sh(
                                        script: "aws opensearchserverless batch-get-collection --names '${collName}' --query 'collectionDetails[0].id' --output text",
                                        returnStdout: true
                                    ).trim()
                                    echo "AOSS collection ready: endpoint=${env.AOSS_COLLECTION_ENDPOINT} id=${env.AOSS_COLLECTION_ID}"
                                }
                            }
                        }
                    }
                }
            }

            stage('Deploy & Bootstrap MA') {
                when { expression { !params.REUSE_EXISTING } }
                steps {
                    timeout(time: 90, unit: 'MINUTES') {
                        script {
                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: params.REGION, duration: 7200, roleSessionName: 'jenkins-session') {
                                    sh """
                                        ./deployment/k8s/aws/aws-bootstrap.sh \
                                          --deploy-create-vpc-cfn \
                                          --build-cfn \
                                          --build-images \
                                          --build-chart-and-dashboards \
                                          --stack-name "${env.STACK_NAME}" \
                                          --stage "${maStageName}" \
                                          --region "${params.REGION}" \
                                          --eks-access-principal-arn "arn:aws:iam::\${MIGRATIONS_TEST_ACCOUNT_ID}:role/JenkinsDeploymentRole" \
                                          --skip-console-exec \
                                          --skip-setting-k8s-context \
                                          2>&1 | while IFS= read -r line; do printf '%s | %s\\n' "\$(date '+%H:%M:%S')" "\$line"; done; exit \${PIPESTATUS[0]}
                                    """
                                }
                            }
                        }
                    }
                }
            }

            stage('Grant MA Roles AOSS Access') {
                when { expression { !params.REUSE_EXISTING } }
                steps {
                    timeout(time: 10, unit: 'MINUTES') {
                        script {
                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: params.REGION, duration: 3600, roleSessionName: 'jenkins-session') {
                                    def podRoleArn = "arn:aws:iam::${MIGRATIONS_TEST_ACCOUNT_ID}:role/${env.eksClusterName}-migrations-role"
                                    def jenkinsRoleArn = "arn:aws:iam::${MIGRATIONS_TEST_ACCOUNT_ID}:role/JenkinsDeploymentRole"
                                    def collName = env.AOSS_COLLECTION_NAME

                                    def policyVersion = sh(
                                        script: "aws opensearchserverless get-access-policy --name '${collName}-access' --type data --query 'accessPolicyDetail.policyVersion' --output text",
                                        returnStdout: true
                                    ).trim()

                                    def updatedPolicy = """[{"Rules":[{"ResourceType":"index","Resource":["index/${collName}/*"],"Permission":["aoss:*"]},{"ResourceType":"collection","Resource":["collection/${collName}"],"Permission":["aoss:*"]}],"Principal":["${jenkinsRoleArn}","${podRoleArn}"]}]"""
                                    sh """aws opensearchserverless update-access-policy \
                                          --name "${collName}-access" \
                                          --type data \
                                          --policy-version "${policyVersion}" \
                                          --policy '${updatedPolicy}'"""
                                    echo "AOSS data access policy updated with pod identity role: ${podRoleArn}"
                                }
                            }
                        }
                    }
                }
            }

            stage('Load Test Data on Source') {
                steps {
                    timeout(time: 30, unit: 'MINUTES') {
                        script {
                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: params.REGION, duration: 3600, roleSessionName: 'jenkins-session') {
                                    sh "kubectl --context=${env.eksKubeContext} wait --namespace ma --for=condition=ready pod/migration-console-0 --timeout=600s"

                                    // Run collection-type-specific OSB workloads on source via migration-console
                                    sh """
                                        kubectl --context=${env.eksKubeContext} exec migration-console-0 -n ma -- bash -lc '
                                            python3 -c "
from console_link.middleware.clusters import run_aoss_test_benchmarks
from console_link.models.cluster import Cluster
cluster = Cluster(config={
    \"endpoint\": \"${env.SOURCE_ENDPOINT}\",
    \"allow_insecure\": True,
    \"sigv4\": {\"region\": \"${params.REGION}\", \"service\": \"es\"}
})
run_aoss_test_benchmarks(cluster, \"${collectionType.toLowerCase()}\")
"
                                        '
                                    """
                                    echo "Test data loaded on source for ${collectionType} collection type"
                                }
                            }
                        }
                    }
                }
            }

            stage('Create Snapshot') {
                steps {
                    timeout(time: 30, unit: 'MINUTES') {
                        script {
                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: params.REGION, duration: 3600, roleSessionName: 'jenkins-session') {
                                    env.SNAPSHOT_NAME = "aoss-test-${maStageName}"
                                    // Get snapshot role and S3 bucket from MA CFN exports
                                    def cfnExports = sh(
                                        script: """aws cloudformation list-exports --region ${params.REGION} \
                                          --query "Exports[?contains(Name, '${maStageName}')].{Name:Name,Value:Value}" --output json""",
                                        returnStdout: true
                                    ).trim()

                                    def snapshotRole = sh(
                                        script: """aws cloudformation list-exports --region ${params.REGION} \
                                          --query "Exports[?starts_with(Name, 'MigrationsExportString')].Value" --output text | \
                                          grep -o 'SNAPSHOT_ROLE=[^;]*' | cut -d= -f2""",
                                        returnStdout: true
                                    ).trim()

                                    def s3Bucket = sh(
                                        script: """aws s3 ls | grep migrations | head -1 | awk '{print \$3}'""",
                                        returnStdout: true
                                    ).trim()
                                    env.S3_REPO_URI = "s3://${s3Bucket}/aoss-snapshot-${maStageName}/"

                                    // Register repo and create snapshot via migration-console
                                    sh """
                                        kubectl --context=${env.eksKubeContext} exec migration-console-0 -n ma -- bash -lc '
                                            curl -s -X PUT "${env.SOURCE_ENDPOINT}/_snapshot/aoss-repo" \
                                              -H "Content-Type: application/json" \
                                              -d "{
                                                \\"type\\": \\"s3\\",
                                                \\"settings\\": {
                                                  \\"bucket\\": \\"${s3Bucket}\\",
                                                  \\"base_path\\": \\"aoss-snapshot-${maStageName}\\",
                                                  \\"region\\": \\"${params.REGION}\\",
                                                  \\"role_arn\\": \\"${snapshotRole}\\"
                                                }
                                              }"

                                            curl -s -X PUT "${env.SOURCE_ENDPOINT}/_snapshot/aoss-repo/${env.SNAPSHOT_NAME}?wait_for_completion=true"
                                        '
                                    """
                                    echo "Snapshot created: ${env.SNAPSHOT_NAME} at ${env.S3_REPO_URI}"
                                }
                            }
                        }
                    }
                }
            }

            stage('Apply Workflow & Run Tests') {
                steps {
                    timeout(time: 60, unit: 'MINUTES') {
                        script {
                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: params.REGION, duration: 3600, roleSessionName: 'jenkins-session') {
                                    sh """
                                        kubectl --context=${env.eksKubeContext} apply -f migrationConsole/lib/integ_test/testWorkflows/fullMigrationImportedClusters.yaml -n ma
                                        echo "Workflow template applied"
                                    """

                                    def s3RepoUri = env.S3_REPO_URI
                                    if (s3RepoUri && !s3RepoUri.endsWith('/')) {
                                        s3RepoUri = s3RepoUri + '/'
                                    }

                                    sh """
                                        cat > /tmp/aoss-env.sh << 'ENVEOF'
export BYOS_SNAPSHOT_NAME='${env.SNAPSHOT_NAME}'
export BYOS_S3_REPO_URI='${s3RepoUri}'
export BYOS_S3_REGION='${params.REGION}'
export BYOS_POD_REPLICAS='${params.RFS_WORKERS}'
export BYOS_MONITOR_RETRY_LIMIT='60'
export ${endpointEnvVar}='${env.AOSS_COLLECTION_ENDPOINT}'
ENVEOF

                                        kubectl --context=${env.eksKubeContext} cp /tmp/aoss-env.sh ma/migration-console-0:/tmp/aoss-env.sh
                                        kubectl --context=${env.eksKubeContext} exec migration-console-0 -n ma -- bash -c '
                                            source /tmp/aoss-env.sh && \
                                            cd /root/lib/integ_test && \
                                            pipenv run pytest integ_test/ma_workflow_test.py \
                                                --source_version=${params.SOURCE_VERSION} \
                                                --target_version=OS_2.x \
                                                --test_ids=${testId} \
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
                timeout(time: 60, unit: 'MINUTES') {
                    script {
                        if (params.SKIP_DELETE) {
                            echo "SKIP_DELETE=true — skipping all resource cleanup"
                            echo "Resources left running:"
                            echo "  AOSS Collection: ${env.AOSS_COLLECTION_NAME} (${env.AOSS_COLLECTION_ID ?: 'unknown'})"
                            echo "  MA Stack: ${env.STACK_NAME}"
                            echo "  Source Domain: ${env.SOURCE_DOMAIN_STACK}"
                            echo "  Network: ${env.NETWORK_STACK}"
                            return
                        }

                        def eksClusterName = env.eksClusterName
                        def collName = env.AOSS_COLLECTION_NAME

                        withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                            withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: params.REGION, duration: 3600, roleSessionName: 'jenkins-session') {
                                // 1. Delete AOSS collection and policies
                                if (env.AOSS_COLLECTION_ID) {
                                    echo "CLEANUP: Deleting AOSS collection ${collName} (${env.AOSS_COLLECTION_ID})"
                                    sh """
                                        aws opensearchserverless delete-collection --id "${env.AOSS_COLLECTION_ID}" || true

                                        echo "Waiting for collection deletion..."
                                        for i in \$(seq 1 30); do
                                            status=\$(aws opensearchserverless batch-get-collection \
                                              --ids "${env.AOSS_COLLECTION_ID}" \
                                              --query 'collectionDetails[0].status' --output text 2>/dev/null)
                                            [ -z "\$status" ] || [ "\$status" = "None" ] && break
                                            echo "  Collection status: \$status (attempt \$i/30)"
                                            sleep 10
                                        done

                                        aws opensearchserverless delete-access-policy --name "${collName}-access" --type data || true
                                        aws opensearchserverless delete-security-policy --name "${collName}-net" --type network || true
                                        aws opensearchserverless delete-security-policy --name "${collName}-enc" --type encryption || true
                                        echo "AOSS resources deleted."
                                    """
                                }

                                // 2. Delete MA CFN stack
                                echo "CLEANUP: Deleting MA stack ${env.STACK_NAME}"
                                sh """
                                    aws cloudformation delete-stack --stack-name "${env.STACK_NAME}" --region "${params.REGION}" || true
                                    aws cloudformation wait stack-delete-complete --stack-name "${env.STACK_NAME}" --region "${params.REGION}" || true
                                    echo "MA stack deleted."
                                """

                                // 3. Cleanup orphaned EKS security groups
                                sh """
                                    echo "CLEANUP: Finding orphaned EKS security groups for cluster ${eksClusterName}"
                                    eks_sgs=\$(aws ec2 describe-security-groups \
                                        --filters "Name=tag:aws:eks:cluster-name,Values=${eksClusterName}" \
                                        --query 'SecurityGroups[*].GroupId' --output text 2>/dev/null || echo "")
                                    if [ -z "\$eks_sgs" ]; then
                                        echo "CLEANUP: No orphaned EKS security groups found"
                                    else
                                        for sg in \$eks_sgs; do
                                            echo "CLEANUP: Deleting EKS security group \$sg"
                                            for i in 1 2 3 4 5; do
                                                if aws ec2 delete-security-group --group-id "\$sg" >/dev/null 2>&1; then
                                                    echo "CLEANUP: Deleted SG \$sg"
                                                    break
                                                fi
                                                echo "CLEANUP: SG \$sg delete failed (attempt \$i), waiting for ENIs to drain..."
                                                sleep 30
                                            done
                                        done
                                    fi
                                """

                                // 4. Delete source domain and network stacks
                                echo "CLEANUP: Deleting source domain stack ${env.SOURCE_DOMAIN_STACK}"
                                sh """
                                    aws cloudformation delete-stack --stack-name "${env.SOURCE_DOMAIN_STACK}" --region "${params.REGION}" || true
                                    aws cloudformation wait stack-delete-complete --stack-name "${env.SOURCE_DOMAIN_STACK}" --region "${params.REGION}" || true
                                    echo "Source domain stack deleted."
                                """

                                echo "CLEANUP: Deleting network stack ${env.NETWORK_STACK}"
                                sh """
                                    aws cloudformation delete-stack --stack-name "${env.NETWORK_STACK}" --region "${params.REGION}" || true
                                    aws cloudformation wait stack-delete-complete --stack-name "${env.NETWORK_STACK}" --region "${params.REGION}" || true
                                    echo "Network stack deleted."
                                """
                            }
                        }

                        sh """
                            if command -v kubectl >/dev/null 2>&1; then
                                kubectl config delete-context ${eksClusterName} 2>/dev/null || true
                            fi
                        """
                        echo "Cleanup completed"
                    }
                }
            }
        }
    }
}
