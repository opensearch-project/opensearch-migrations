static def expandVersionString(String input) {
    def trimmed = input.trim()
    def pattern = ~/^(ES|OS)_(\d+)\.(\d+)$/
    def matcher = trimmed =~ pattern
    if (!matcher.matches()) {
        error("Invalid version string format: '${input}'. Expected something like ES_7.10 or OS_1.3")
    }
    def prefix = matcher[0][1]
    def major  = matcher[0][2]
    def minor  = matcher[0][3]
    def name   = (prefix == 'ES') ? 'elasticsearch' : 'opensearch'
    return "${name}-${major}-${minor}"
}

def call(Map config = [:]) {
    def defaultStageId = config.defaultStageId ?: "kiro-sop"
    def jobName = config.jobName ?: "kiro-sop-test"
    def sourceVersion = config.sourceVersion ?: ""
    def targetVersion = config.targetVersion ?: ""
    def sourceClusterType = config.sourceClusterType ?: ""
    def targetClusterType = config.targetClusterType ?: ""
    def clusterContextFilePath = "tmp/cluster-context-kiro-sop-${currentBuild.number}.json"
    pipeline {
        agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }

        parameters {
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/opensearch-project/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Git branch to use for repository')
            string(name: 'STAGE', defaultValue: "${defaultStageId}", description: 'Stage name for deployment environment')
            choice(name: 'SOURCE_VERSION', choices: ['ES_7.10'], description: 'Source version')
            choice(name: 'SOURCE_CLUSTER_TYPE', choices: ['OPENSEARCH_MANAGED_SERVICE'], description: 'Source cluster type')
            choice(name: 'TARGET_VERSION', choices: ['OS_2.19'], description: 'Target version')
            choice(name: 'TARGET_CLUSTER_TYPE', choices: ['OPENSEARCH_MANAGED_SERVICE'], description: 'Target cluster type')
            string(name: 'MIN_SCORE', defaultValue: '70', description: 'Minimum passing score (0-100)')
        }

        options {
            lock(label: params.STAGE, quantity: 1, variable: 'maStageName')
            timeout(time: 4, unit: 'HOURS')
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
                    causeString: 'Triggered by PR on opensearch-migrations repository',
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
                    script { sh 'aws sts get-caller-identity' }
                }
            }

            stage('Build') {
                steps {
                    timeout(time: 1, unit: 'HOURS') {
                        script { sh './gradlew clean build --no-daemon --stacktrace' }
                    }
                }
            }

            stage('Deploy Clusters') {
                steps {
                    timeout(time: 60, unit: 'MINUTES') {
                        dir('test') {
                            script {
                                env.sourceVer = sourceVersion ?: params.SOURCE_VERSION
                                env.targetVer = targetVersion ?: params.TARGET_VERSION
                                env.sourceClusterType = sourceClusterType ?: params.SOURCE_CLUSTER_TYPE
                                env.targetClusterType = targetClusterType ?: params.TARGET_CLUSTER_TYPE
                                deployClustersStep(
                                    stage: "${maStageName}",
                                    clusterContextFilePath: "${clusterContextFilePath}",
                                    sourceVer: env.sourceVer,
                                    sourceClusterType: env.sourceClusterType,
                                    targetVer: env.targetVer,
                                    targetClusterType: env.targetClusterType
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
                                env.STACK_NAME_SUFFIX = "${maStageName}-us-east-1"
                                def clusterDetails = readJSON text: env.clusterDetailsJson
                                def targetCluster = clusterDetails.target
                                sh "npm install"
                                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: "us-east-1", duration: 3600, roleSessionName: 'jenkins-session') {
                                        sh """
                                            cdk deploy Migration-Assistant-Infra-Import-VPC-eks-${env.STACK_NAME_SUFFIX} \
                                              --parameters Stage=${maStageName} \
                                              --parameters VPCId=${targetCluster.vpcId} \
                                              --parameters VPCSubnetIds=${targetCluster.subnetIds} \
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
                                        error("Could not retrieve MigrationsExportString")
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
                                          echo "Access entry already exists"
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
                                            echo "kubectl ready"
                                            break
                                          fi
                                          echo "Waiting for kubectl... (\$i/10)"
                                          sleep 5
                                        done
                                    """

                                    sh 'kubectl create namespace ma --dry-run=client -o yaml | kubectl apply -f -'
                                    def clusterDetails = readJSON text: env.clusterDetailsJson
                                    def sourceCluster = clusterDetails.source
                                    def targetCluster = clusterDetails.target
                                    def sourceVersionExpanded = expandVersionString("${env.sourceVer}")
                                    def targetVersionExpanded = expandVersionString("${env.targetVer}")

                                    writeJSON file: '/tmp/source-cluster-config.json', json: [
                                            endpoint: sourceCluster.endpoint,
                                            allow_insecure: true,
                                            sigv4: [region: "us-east-1", service: "es"],
                                            version: env.sourceVer
                                    ]
                                    sh """
                                      kubectl create configmap source-${sourceVersionExpanded}-migration-config \
                                        --from-file=cluster-config=/tmp/source-cluster-config.json \
                                        --namespace ma --dry-run=client -o yaml | kubectl apply -f -
                                    """
                                    writeJSON file: '/tmp/target-cluster-config.json', json: [
                                            endpoint: targetCluster.endpoint,
                                            allow_insecure: true,
                                            sigv4: [region: "us-east-1", service: "es"],
                                            version: env.targetVer
                                    ]
                                    sh """
                                      kubectl create configmap target-${targetVersionExpanded}-migration-config \
                                        --from-file=cluster-config=/tmp/target-cluster-config.json \
                                        --namespace ma --dry-run=client -o yaml | kubectl apply -f -
                                    """

                                    sh """
                                      exists=\$(aws ec2 describe-security-groups \
                                        --group-ids $targetCluster.securityGroupId \
                                        --query "SecurityGroups[0].IpPermissions[?UserIdGroupPairs[?GroupId=='$env.clusterSecurityGroup']]" \
                                        --output text)
                                      if [ -z "\$exists" ]; then
                                        aws ec2 authorize-security-group-ingress \
                                          --group-id $targetCluster.securityGroupId \
                                          --protocol -1 --port -1 \
                                          --source-group $env.clusterSecurityGroup
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
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: "us-east-1", duration: 3600, roleSessionName: 'jenkins-session') {
                                    sh "docker run --privileged --rm tonistiigi/binfmt --install all"
                                    def builderExists = sh(
                                            script: "docker buildx ls | grep -q '^ecr-builder'",
                                            returnStatus: true
                                    ) == 0
                                    if (!builderExists) {
                                        sh "docker buildx create --name ecr-builder --driver docker-container"
                                    }
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
                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: "us-east-1", duration: 3600, roleSessionName: 'jenkins-session') {
                                        sh "./aws-bootstrap.sh --skip-git-pull --base-dir ${WORKSPACE} --use-public-images false --skip-console-exec --stage ${maStageName}"
                                    }
                                }
                            }
                        }
                    }
                }
            }

            stage('Seed Source Data') {
                steps {
                    timeout(time: 15, unit: 'MINUTES') {
                        script {
                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: "us-east-1", duration: 3600, roleSessionName: 'jenkins-session') {
                                    // Wait for migration-console pod to be ready
                                    sh """
                                        kubectl wait --for=condition=Ready pod/migration-console-0 -n ma --timeout=300s
                                    """
                                    // Seed test data on source cluster via migration console
                                    sh """
                                        kubectl exec migration-console-0 -n ma -- bash -c 'source /.venv/bin/activate && console clusters run-test-benchmarks' || echo 'Benchmark seeding attempted'
                                    """
                                    // Verify source has data
                                    sh """
                                        kubectl exec migration-console-0 -n ma -- bash -c 'source /.venv/bin/activate && console clusters cat-indices'
                                    """
                                }
                            }
                        }
                    }
                }
            }

            stage('Install kiro-cli') {
                steps {
                    timeout(time: 10, unit: 'MINUTES') {
                        script {
                            sh """
                                curl -fsSL https://desktop-release.q.us-east-1.amazonaws.com/latest/kiro-cli/linux/x64/kiro-cli.tar.gz | tar -xz -C /tmp
                                sudo mv /tmp/kiro-cli /usr/local/bin/kiro-cli
                                kiro-cli --version
                            """
                        }
                    }
                }
            }

            stage('Setup kiro-cli Auth') {
                steps {
                    timeout(time: 5, unit: 'MINUTES') {
                        script {
                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: "us-east-1", duration: 3600, roleSessionName: 'jenkins-session') {
                                    sh "python3 test/kiroSopTest/setup_kiro_auth.py"
                                }
                            }
                        }
                    }
                }
            }

            stage('Run Kiro SOP Agent') {
                steps {
                    timeout(time: 2, unit: 'HOURS') {
                        script {
                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: "us-east-1", duration: 7200, roleSessionName: 'jenkins-session') {
                                    def clusterDetails = readJSON text: env.clusterDetailsJson
                                    def sourceEp = clusterDetails.source.endpoint
                                    def targetEp = clusterDetails.target.endpoint

                                    // Setup kiro-cli config from repo
                                    sh """
                                        mkdir -p ~/.kiro
                                        cp -r kiro-cli/kiro-cli-config/* ~/.kiro/
                                        cp agent-sops/opensearch-migration-assistant-eks.sop.md ~/.kiro/steering/
                                    """

                                    // Build the prompt for kiro-cli
                                    def prompt = """Execute a complete OpenSearch migration with these parameters:

hands_on_level: auto
allow_destructive: true
namespace: ma
ma_environment_mode: use_existing_stage
stage: ${maStageName}
aws_region: us-east-1

source_cluster:
  endpoint: ${sourceEp}
  version: ${env.sourceVer}
  auth: sigv4 (region: us-east-1, service: es)

target_cluster:
  endpoint: ${targetEp}
  version: ${env.targetVer}
  auth: sigv4 (region: us-east-1, service: es)

ENVIRONMENT STATUS:
- EKS cluster is deployed and kubectl is configured
- Helm chart is installed in namespace 'ma'
- migration-console-0 pod is running
- Skip SOP Step 0 (environment acquisition) - it's already done
- Start from Step 1 (Initialize Run Workspace)

CRITICAL: When migration is complete, output MIGRATION_COMPLETE. If failed, output MIGRATION_FAILED: <reason>."""

                                    writeFile file: '/tmp/kiro-prompt.txt', text: prompt

                                    // Run kiro-cli with the opensearch-migration agent
                                    sh """
                                        cd ${WORKSPACE}
                                        cat /tmp/kiro-prompt.txt | kiro-cli chat --agent opensearch-migration --trust-all-tools 2>&1 | tee ${WORKSPACE}/kiro-cli-output.log || true
                                    """
                                }
                            }
                        }
                    }
                }
            }

            stage('Evaluate Results') {
                steps {
                    timeout(time: 10, unit: 'MINUTES') {
                        script {
                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: "us-east-1", duration: 3600, roleSessionName: 'jenkins-session') {
                                    sh """
                                        python3 test/kiroSopTest/evaluate.py \
                                          --namespace ma \
                                          --output '${WORKSPACE}/sop-test-report.json' \
                                          --min-score '${params.MIN_SCORE}'
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
                        // Archive test reports and logs
                        archiveArtifacts artifacts: 'sop-test-report*.json,kiro-cli-output.log', allowEmptyArchive: true

                        if (env.eksClusterName) {
                            def clusterDetails = readJSON text: env.clusterDetailsJson
                            def targetCluster = clusterDetails.target
                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: "us-east-1", duration: 4500, roleSessionName: 'jenkins-session') {
                                    sh "kubectl -n ma get pods || true"
                                    // Cleanup helm and namespace
                                    sh "helm uninstall -n ma ma --wait --timeout 60s || true"
                                    sh "kubectl get all,pvc,configmap,secret,workflow -n ma -o wide --ignore-not-found || true"
                                    sh "kubectl delete namespace ma --ignore-not-found --timeout=60s || true"
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
                                            --protocol -1 --port -1 \
                                            --source-group $env.clusterSecurityGroup
                                        fi
                                      fi
                                    """
                                    sh "cd $WORKSPACE/deployment/migration-assistant-solution && cdk destroy Migration-Assistant-Infra-Import-VPC-eks-${env.STACK_NAME_SUFFIX} --force --concurrency 3"
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
