def call(Map config = [:]) {
    def collectionType = config.collectionType ?: 'SEARCH'
    def testIdMap = ['SEARCH': '0021', 'TIMESERIES': '0022', 'VECTORSEARCH': '0023']
    def envVarMap = ['SEARCH': 'AOSS_SEARCH_ENDPOINT', 'TIMESERIES': 'AOSS_TIMESERIES_ENDPOINT', 'VECTORSEARCH': 'AOSS_VECTOR_ENDPOINT']
    def testId = testIdMap[collectionType]
    def endpointEnvVar = envVarMap[collectionType]
    def benchmarkTypeMap = ['SEARCH': 'search', 'TIMESERIES': 'timeseries', 'VECTORSEARCH': 'vector']
    def benchmarkType = benchmarkTypeMap[collectionType]
    def defaultStageId = config.defaultStageId ?: "aosssrch"
    def jobName = config.jobName ?: "eks-aoss-${collectionType.toLowerCase()}-integ-test"
    def clusterContextFilePath = "tmp/cluster-context-aoss-${currentBuild.number}.json"

    pipeline {
        agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }

        parameters {
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/jugal-chauhan/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: 'jenkins-target-aoss-collection', description: 'Git branch to use for repository')
            string(name: 'STAGE', defaultValue: "${defaultStageId}", description: 'Stage name for deployment environment')
            string(name: 'REGION', defaultValue: 'us-east-1', description: 'AWS region for deployment')
            choice(name: 'SOURCE_VERSION', choices: ['ES_7.10'], description: 'Version of the source cluster')
            string(name: 'RFS_WORKERS', defaultValue: '1', description: 'Number of RFS worker pods for document backfill')
            booleanParam(name: 'SKIP_DELETE', defaultValue: false, description: 'Skip deletion of all resources after test (for debugging)')
            booleanParam(name: 'REUSE_EXISTING', defaultValue: false, description: 'Reuse existing source, target, and MA resources')
        }

        options {
            lock(label: params.STAGE ?: defaultStageId, quantity: 1, variable: 'maStageName')
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
                        env.STACK_NAME = "MA-Serverless-${maStageName}-${params.REGION}"
                        env.eksClusterName = "migration-eks-cluster-${maStageName}-${params.REGION}"
                        env.eksKubeContext = env.eksClusterName
                        env.CLUSTER_STACK = "OpenSearch-${maStageName}-${params.REGION}"

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
                            sh './gradlew clean build -x test --no-daemon --stacktrace'
                        }
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
                                    def stackOutputs = sh(
                                        script: """aws cloudformation describe-stacks --stack-name "${env.CLUSTER_STACK}" \
                                          --query 'Stacks[0].Outputs' --output json""",
                                        returnStdout: true
                                    ).trim()
                                    writeFile file: '/tmp/stack-outputs.json', text: stackOutputs

                                    env.SOURCE_ENDPOINT = "https://" + sh(
                                        script: "jq -r '.[] | select(.OutputKey==\"ClusterEndpointExport${maStageName}source\") | .OutputValue' /tmp/stack-outputs.json",
                                        returnStdout: true
                                    ).trim()
                                    env.AOSS_COLLECTION_ENDPOINT = sh(
                                        script: "jq -r '.[] | select(.OutputKey==\"CollectionEndpointExport${maStageName}target\") | .OutputValue' /tmp/stack-outputs.json",
                                        returnStdout: true
                                    ).trim()
                                    echo "Discovered source: ${env.SOURCE_ENDPOINT}"
                                    echo "Discovered AOSS: ${env.AOSS_COLLECTION_ENDPOINT}"

                                    sh "aws eks update-kubeconfig --region ${params.REGION} --name ${env.eksClusterName} --alias ${env.eksClusterName}"

                                    env.SNAPSHOT_NAME = "aoss-test-${maStageName}"
                                    def s3Bucket = sh(
                                        script: "kubectl --context=${env.eksClusterName} get configmap migrations-default-s3-config -n ma -o jsonpath='{.data.BUCKET_NAME}'",
                                        returnStdout: true
                                    ).trim()
                                    env.S3_REPO_URI = "s3://${s3Bucket}/aoss-snapshot-${maStageName}/"
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
                                          --base-dir "\$(pwd)" \
                                          --skip-console-exec \
                                          --skip-setting-k8s-context \
                                          2>&1 | { set +x; while IFS= read -r line; do printf '%s | %s\\n' "\$(date '+%H:%M:%S')" "\$line"; done; }; exit \${PIPESTATUS[0]}
                                    """

                                    // Capture MA VPC info for source domain deployment
                                    def exportsJson = sh(
                                        script: """aws cloudformation describe-stacks --stack-name "${env.STACK_NAME}" \
                                          --query 'Stacks[0].Outputs' --output json""",
                                        returnStdout: true
                                    ).trim()
                                    writeFile file: '/tmp/ma-outputs.json', text: exportsJson

                                    env.MA_VPC_ID = sh(
                                        script: "jq -r '[.[] | select(.OutputKey | test(\"VpcId\"))] | first | .OutputValue' /tmp/ma-outputs.json",
                                        returnStdout: true
                                    ).trim()

                                    echo "MA VPC: ${env.MA_VPC_ID}"
                                }
                            }
                        }
                    }
                }
            }

            stage('Deploy Source + AOSS Target') {
                when { expression { !params.REUSE_EXISTING } }
                steps {
                    timeout(time: 60, unit: 'MINUTES') {
                        dir('test') {
                            script {
                                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                    def jenkinsRoleArn = "arn:aws:iam::${MIGRATIONS_TEST_ACCOUNT_ID}:role/JenkinsDeploymentRole"
                                    def podRoleArn = "arn:aws:iam::${MIGRATIONS_TEST_ACCOUNT_ID}:role/${env.eksClusterName}-migrations-role"

                                    def context = [
                                        stage: "${maStageName}",
                                        vpcId: "${env.MA_VPC_ID}",
                                        clusters: [
                                            [
                                                clusterId: "source",
                                                clusterVersion: "${params.SOURCE_VERSION}",
                                                clusterType: "OPENSEARCH_MANAGED_SERVICE",
                                                openAccessPolicyEnabled: true,
                                                allowAllVpcTraffic: true,
                                                domainRemovalPolicy: "DESTROY"
                                            ],
                                            [
                                                clusterId: "target",
                                                clusterType: "OPENSEARCH_SERVERLESS",
                                                collectionType: collectionType,
                                                standbyReplicas: "DISABLED",
                                                domainRemovalPolicy: "DESTROY",
                                                dataAccessPrincipals: [jenkinsRoleArn, podRoleArn]
                                            ]
                                        ]
                                    ]
                                    def contextJson = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(context))
                                    writeFile(file: clusterContextFilePath, text: contextJson)
                                    sh "echo 'Cluster context:' && cat ${clusterContextFilePath}"

                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: params.REGION, duration: 3600, roleSessionName: 'jenkins-session') {
                                        sh "./awsDeployCluster.sh --stage ${maStageName} --context-file ${clusterContextFilePath}"
                                    }

                                    def clusterDetails = readJSON text: readFile("tmp/cluster-details-${maStageName}.json")
                                    env.SOURCE_ENDPOINT = clusterDetails.source.endpoint
                                    env.AOSS_COLLECTION_ENDPOINT = clusterDetails['target'].endpoint
                                    echo "Source: ${env.SOURCE_ENDPOINT}"
                                    echo "AOSS: ${env.AOSS_COLLECTION_ENDPOINT}"
                                }
                            }
                        }
                    }
                }
            }

            stage('Load Test Data on Source') {
                when { expression { !params.REUSE_EXISTING } }
                steps {
                    timeout(time: 30, unit: 'MINUTES') {
                        script {
                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: params.REGION, duration: 3600, roleSessionName: 'jenkins-session') {
                                    sh "kubectl --context=${env.eksKubeContext} wait --namespace ma --for=condition=ready pod/migration-console-0 --timeout=600s"

                                    def loadDataScript = """
from console_link.middleware.clusters import run_aoss_test_benchmarks
from console_link.models.cluster import Cluster
cluster = Cluster(config={
    "endpoint": "${env.SOURCE_ENDPOINT}",
    "allow_insecure": True,
    "no_auth": None
})
run_aoss_test_benchmarks(cluster, "${benchmarkType}")
"""
                                    writeFile file: '/tmp/load-data.py', text: loadDataScript
                                    sh """
                                        kubectl --context=${env.eksKubeContext} cp /tmp/load-data.py ma/migration-console-0:/tmp/load-data.py
                                        kubectl --context=${env.eksKubeContext} exec migration-console-0 -n ma -- bash -c 'source /.venv/bin/activate && python3 /tmp/load-data.py'
                                    """
                                    echo "Test data loaded on source for ${collectionType} collection type"
                                }
                            }
                        }
                    }
                }
            }

            stage('Create Snapshot') {
                when { expression { !params.REUSE_EXISTING } }
                steps {
                    timeout(time: 30, unit: 'MINUTES') {
                        script {
                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: params.REGION, duration: 3600, roleSessionName: 'jenkins-session') {
                                    env.SNAPSHOT_NAME = "aoss-test-${maStageName}"

                                    def snapshotRole = sh(
                                        script: """aws cloudformation list-exports --region ${params.REGION} \
                                          --query "Exports[?starts_with(Name, 'MigrationsExportString')].Value" --output text | \
                                          grep -o 'SNAPSHOT_ROLE=[^;]*' | cut -d= -f2""",
                                        returnStdout: true
                                    ).trim()

                                    def s3Bucket = sh(
                                        script: "kubectl --context=${env.eksKubeContext} get configmap migrations-default-s3-config -n ma -o jsonpath='{.data.BUCKET_NAME}'",
                                        returnStdout: true
                                    ).trim()
                                    env.S3_REPO_URI = "s3://${s3Bucket}/aoss-snapshot-${maStageName}/"

                                    def repoConfig = """{"type":"s3","settings":{"bucket":"${s3Bucket}","base_path":"aoss-snapshot-${maStageName}","region":"${params.REGION}","role_arn":"${snapshotRole}"}}"""
                                    writeFile file: '/tmp/snapshot-repo-config.json', text: repoConfig

                                    def snapshotScript = """
import json, requests
endpoint = "${env.SOURCE_ENDPOINT}"
repo_url = f"{endpoint}/_snapshot/aoss-repo"
with open("/tmp/snapshot-repo-config.json") as f:
    repo_config = json.load(f)
r = requests.put(repo_url, json=repo_config, verify=False)
print(f"Register repo: {r.status_code} {r.text}")
r.raise_for_status()
snap_url = f"{endpoint}/_snapshot/aoss-repo/${env.SNAPSHOT_NAME}?wait_for_completion=true"
r = requests.put(snap_url, verify=False)
print(f"Create snapshot: {r.status_code} {r.text}")
r.raise_for_status()
"""
                                    writeFile file: '/tmp/create-snapshot.py', text: snapshotScript

                                    sh """
                                        kubectl --context=${env.eksKubeContext} cp /tmp/snapshot-repo-config.json ma/migration-console-0:/tmp/snapshot-repo-config.json
                                        kubectl --context=${env.eksKubeContext} cp /tmp/create-snapshot.py ma/migration-console-0:/tmp/create-snapshot.py
                                        kubectl --context=${env.eksKubeContext} exec migration-console-0 -n ma -- bash -c 'source /.venv/bin/activate && python3 /tmp/create-snapshot.py'
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
                            echo "  Cluster Stack: ${env.CLUSTER_STACK}"
                            echo "  MA Stack: ${env.STACK_NAME}"
                            return
                        }

                        def eksClusterName = env.eksClusterName

                        withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                            withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: params.REGION, duration: 3600, roleSessionName: 'jenkins-session') {
                                // 1. Delete cluster stack first (source domain + AOSS — depends on MA VPC)
                                echo "CLEANUP: Deleting cluster stack ${env.CLUSTER_STACK}"
                                sh """
                                    aws cloudformation delete-stack --stack-name "${env.CLUSTER_STACK}" --region "${params.REGION}" || true
                                    aws cloudformation wait stack-delete-complete --stack-name "${env.CLUSTER_STACK}" --region "${params.REGION}" || true
                                    echo "Cluster stack deleted."
                                """

                                // 2. Cleanup orphaned EKS security groups
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

                                // 3. Delete MA stack last (owns the VPC)
                                echo "CLEANUP: Deleting MA stack ${env.STACK_NAME}"
                                sh """
                                    aws cloudformation delete-stack --stack-name "${env.STACK_NAME}" --region "${params.REGION}" || true
                                    aws cloudformation wait stack-delete-complete --stack-name "${env.STACK_NAME}" --region "${params.REGION}" || true
                                    echo "MA stack deleted."
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
