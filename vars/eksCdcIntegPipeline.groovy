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
    def defaultStageId = config.defaultStageId ?: "cdc-only"
    def jobName = config.jobName ?: "eks-cdc-integ-test"
    def defaultTestIds = config.defaultTestIds ?: "0030"
    def lockLabel = config.lockLabel ?: (jobName.startsWith("pr-") ? "aws-pr-slot" : "aws-main-slot")
    def sourceVersion = config.sourceVersion ?: ""
    def targetVersion = config.targetVersion ?: ""
    def sourceClusterType = config.sourceClusterType ?: ""
    def targetClusterType = config.targetClusterType ?: ""
    def clusterContextFilePath = "tmp/cluster-context-cdc-integ-${currentBuild.number}.json"
    pipeline {
        agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }

        parameters {
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/opensearch-project/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Git branch to use for repository')
            string(name: 'GIT_COMMIT', defaultValue: '', description: '(Optional) Specific commit to checkout after cloning branch')
            string(name: 'TEST_IDS', defaultValue: "${defaultTestIds}", description: 'Comma-separated test IDs to run (e.g. 0030)')
            string(name: 'STAGE', defaultValue: "${defaultStageId}", description: 'Stage name for deployment environment')
            choice(
                    name: 'SOURCE_VERSION',
                    choices: ['ES_7.10'],
                    description: 'Pick a specific source version'
            )
            choice(
                    name: 'SOURCE_CLUSTER_TYPE',
                    choices: ['OPENSEARCH_MANAGED_SERVICE'],
                    description: 'Pick a source cluster type'
            )
            choice(
                    name: 'TARGET_VERSION',
                    choices: ['OS_1.3', 'OS_2.19', 'OS_3.1'],
                    description: 'Pick a specific target version'
            )
            choice(
                    name: 'TARGET_CLUSTER_TYPE',
                    choices: ['OPENSEARCH_MANAGED_SERVICE'],
                    description: 'Pick a target cluster type'
            )
            string(name: 'REGION', defaultValue: 'us-east-1', description: 'AWS region for deployment')
            booleanParam(name: 'BUILD_IMAGES', defaultValue: true, description: 'Build container images from source instead of using public images')
            booleanParam(name: 'BUILD_CHART_AND_DASHBOARDS', defaultValue: true, description: 'Build Helm chart and dashboards from source instead of using release artifacts')
            booleanParam(name: 'USE_RELEASE_BOOTSTRAP', defaultValue: false, description: 'Download aws-bootstrap.sh from the latest GitHub release instead of using the source checkout version')
            string(name: 'VERSION', defaultValue: 'latest', description: 'Release version to deploy (e.g. "2.8.2" or "latest"). Determines which release artifacts to download.')
        }

        options {
            lock(label: lockLabel, quantity: 1)
            timeout(time: 3, unit: 'HOURS')
            buildDiscarder(logRotator(daysToKeepStr: '30'))
            skipDefaultCheckout(true)
        }

        triggers {
            GenericTrigger(
                    genericVariables: [
                            [key: 'GIT_REPO_URL', value: '$.GIT_REPO_URL'],
                            [key: 'GIT_BRANCH', value: '$.GIT_BRANCH'],
                            [key: 'GIT_COMMIT', value: '$.GIT_COMMIT'],
                            [key: 'job_name', value: '$.job_name']
                    ],
                    tokenCredentialId: 'jenkins-migrations-generic-webhook-token',
                    causeString: 'Triggered by PR on opensearch-migrations repository',
                    regexpFilterExpression: "^$jobName\$",
                    regexpFilterText: "\$job_name",
            )
        }

        stages {
            stage('Checkout & Print Params') {
                steps {
                    script { env.maStageName = "${params.STAGE}-${currentBuild.number}" }
                    checkoutStep(branch: params.GIT_BRANCH, repo: params.GIT_REPO_URL, commit: params.GIT_COMMIT)
                    echo """
    ================================================================
    EKS CDC Integration Test
    ================================================================
    Git:                    ${params.GIT_REPO_URL} @ ${params.GIT_BRANCH}
    Commit:                 ${params.GIT_COMMIT ?: '(latest)'}
    Stage:                  ${env.maStageName}
    Region:                 ${params.REGION}
    Test IDs:               ${params.TEST_IDS}
    Source:                 ${params.SOURCE_VERSION}
    Target:                 ${params.TARGET_VERSION}
    Build Images:           ${params.BUILD_IMAGES}
    Build Chart:            ${params.BUILD_CHART_AND_DASHBOARDS}
    Use Release Bootstrap:  ${params.USE_RELEASE_BOOTSTRAP}
    Version:                ${params.VERSION}
    ================================================================
"""
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
                                    targetClusterType: env.targetClusterType,
                                    region: params.REGION
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
                            def vpcId = targetCluster.vpcId ?: ''
                            def subnetIds = "${targetCluster.subnetIds}"

                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: params.REGION, duration: 3600, roleSessionName: 'jenkins-session') {
                                    if (!vpcId) {
                                        def firstSubnet = subnetIds.split(',')[0]
                                        vpcId = sh(script: "aws ec2 describe-subnets --subnet-ids ${firstSubnet} --region ${params.REGION} --query 'Subnets[0].VpcId' --output text", returnStdout: true).trim()
                                        echo "Resolved VPC ID from subnet: ${vpcId}"
                                    }
                                    def bootstrap = resolveBootstrap(
                                        useReleaseBootstrap: params.USE_RELEASE_BOOTSTRAP,
                                        buildImages: params.BUILD_IMAGES,
                                        buildChartAndDashboards: params.BUILD_CHART_AND_DASHBOARDS,
                                        skipTestImages: true,
                                        version: params.VERSION
                                    )
                                    sh """
                                        ${bootstrap.script} \
                                          --deploy-import-vpc-cfn \
                                          --stack-name "Migration-Assistant-Infra-Import-VPC-eks-${env.STACK_NAME_SUFFIX}" \
                                          --vpc-id "${vpcId}" \
                                          --subnet-ids "${subnetIds}" \
                                          --stage "${maStageName}" \
                                          --eks-access-principal-arn "arn:aws:iam::\${MIGRATIONS_TEST_ACCOUNT_ID}:role/JenkinsDeploymentRole" \
                                          ${bootstrap.flags} \
                                          --skip-console-exec \
                                          --skip-setting-k8s-context \
                                          --region ${params.REGION} \
                                          2>&1 | { set +x; while IFS= read -r line; do printf '%s | %s\\n' "\$(date '+%H:%M:%S')" "\$line"; done; }; exit \${PIPESTATUS[0]}
                                    """

                                    def rawOutput = sh(
                                        script: """
                                          aws cloudformation describe-stacks \
                                          --stack-name Migration-Assistant-Infra-Import-VPC-eks-${env.STACK_NAME_SUFFIX} \
                                          --query "Stacks[0].Outputs[?OutputKey=='MigrationsExportString'].OutputValue" \
                                          --output text
                                        """,
                                        returnStdout: true
                                    ).trim()
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
                                    env.eksKubeContext = "migration-eks-cluster-${maStageName}-${params.REGION}"
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
                                    def sourceCluster = clusterDetails.source
                                    def targetCluster = clusterDetails.target
                                    def sourceVersionExpanded = expandVersionString("${env.sourceVer}")
                                    def targetVersionExpanded = expandVersionString("${env.targetVer}")

                                    // Source configmap
                                    writeJSON file: '/tmp/source-cluster-config.json', json: [
                                            endpoint: sourceCluster.endpoint,
                                            allow_insecure: true,
                                            sigv4: [
                                                    region: params.REGION,
                                                    service: "es"
                                            ],
                                            version: env.sourceVer
                                    ]
                                    sh """
                                      kubectl --context=${env.eksKubeContext} create configmap source-${sourceVersionExpanded}-migration-config \
                                        --from-file=cluster-config=/tmp/source-cluster-config.json \
                                        --namespace ma --dry-run=client -o yaml | kubectl --context=${env.eksKubeContext} apply -f -

                                      kubectl --context=${env.eksKubeContext} -n ma get configmap source-${sourceVersionExpanded}-migration-config -o yaml
                                    """

                                    // Target configmap
                                    writeJSON file: '/tmp/target-cluster-config.json', json: [
                                            endpoint: targetCluster.endpoint,
                                            allow_insecure: true,
                                            sigv4: [
                                                    region: params.REGION,
                                                    service: "es"
                                            ]
                                    ]
                                    sh """
                                      kubectl --context=${env.eksKubeContext} create configmap target-${targetVersionExpanded}-migration-config \
                                        --from-file=cluster-config=/tmp/target-cluster-config.json \
                                        --namespace ma --dry-run=client -o yaml | kubectl --context=${env.eksKubeContext} apply -f -

                                      kubectl --context=${env.eksKubeContext} -n ma get configmap target-${targetVersionExpanded}-migration-config -o yaml
                                    """

                                    // Modify source/target security group to allow EKS cluster security group
                                    sh """
                                      exists=\$(aws ec2 describe-security-groups \
                                        --group-ids $targetCluster.securityGroupId \
                                        --query "SecurityGroups[0].IpPermissions[?UserIdGroupPairs[?GroupId=='$env.clusterSecurityGroup']]" \
                                        --output text)

                                      if [ -z "\$exists" ]; then
                                        echo "Adding EKS SG ingress to target cluster SG..."
                                        aws ec2 authorize-security-group-ingress \
                                          --group-id $targetCluster.securityGroupId \
                                          --protocol -1 \
                                          --port -1 \
                                          --source-group $env.clusterSecurityGroup
                                      else
                                        echo "Target cluster SG ingress rule already exists. Skipping."
                                      fi
                                    """

                                    // Allow EKS cluster SG to reach source cluster (needed for capture proxy)
                                    sh """
                                      exists=\$(aws ec2 describe-security-groups \
                                        --group-ids $sourceCluster.securityGroupId \
                                        --query "SecurityGroups[0].IpPermissions[?UserIdGroupPairs[?GroupId=='$env.clusterSecurityGroup']]" \
                                        --output text)

                                      if [ -z "\$exists" ]; then
                                        echo "Adding EKS SG ingress to source cluster SG..."
                                        aws ec2 authorize-security-group-ingress \
                                          --group-id $sourceCluster.securityGroupId \
                                          --protocol -1 \
                                          --port -1 \
                                          --source-group $env.clusterSecurityGroup
                                      else
                                        echo "Source cluster SG ingress rule already exists. Skipping."
                                      fi
                                    """
                                }
                            }
                        }
                    }
                }
            }

            stage('Perform CDC E2E Tests') {
                steps {
                    timeout(time: 2, unit: 'HOURS') {
                        dir('libraries/testAutomation') {
                            script {
                                sh "pipenv install --deploy"
                                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: params.REGION, duration: 3600, roleSessionName: 'jenkins-session') {
                                        sh "pipenv run app --source-version=$sourceVer --target-version=$targetVer --test-ids='${params.TEST_IDS}' --reuse-clusters --skip-delete --skip-install --kube-context=${env.eksKubeContext}"
                                    }
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
                        def region = params.REGION ?: 'us-east-1'
                        def clusterStackName = "OpenSearch-${maStageName}-${region}"
                        def maStackName = "Migration-Assistant-Infra-Import-VPC-eks-${maStageName}-${region}"

                        withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                            withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: region, duration: 4500, roleSessionName: 'jenkins-session') {
                                if (env.eksClusterName) {
                                    dir('libraries/testAutomation') {
                                        sh "pipenv install --deploy"
                                        sh "kubectl --context=${env.eksKubeContext} -n ma get pods || true"
                                        sh "mkdir -p logs"
                                        archiveArtifacts artifacts: 'logs/**', allowEmptyArchive: true
                                        // Stop any running Argo workflows first to prevent finalizer hangs
                                        sh "kubectl --context=${env.eksKubeContext} -n ma delete workflows --all --timeout=60s || true"
                                        sh "pipenv run app --delete-only --kube-context=${env.eksKubeContext}"
                                        echo "List resources not removed by helm uninstall:"
                                        sh "kubectl --context=${env.eksKubeContext} get all,pvc,configmap,secret,workflow -n ma -o wide --ignore-not-found || true"
                                        // Remove finalizers from any stuck resources before namespace deletion
                                        sh """
                                          for resource in \$(kubectl --context=${env.eksKubeContext} get kafka,kafkanodepool,workflows -n ma -o name 2>/dev/null); do
                                            kubectl --context=${env.eksKubeContext} patch \$resource -n ma --type=merge -p '{"metadata":{"finalizers":[]}}' || true
                                          done
                                        """
                                        // Delete the LoadBalancer service explicitly (NLB deprovisioning can block namespace deletion)
                                        sh "kubectl --context=${env.eksKubeContext} delete service capture-proxy -n ma --timeout=30s || true"
                                        sh "kubectl --context=${env.eksKubeContext} delete namespace ma --ignore-not-found --timeout=120s || true"
                                        sh "kubectl --context=${env.eksKubeContext} wait --for=delete namespace/ma --timeout=300s || true"
                                    }

                                    // Revoke security group rules added during setup
                                    if (env.clusterDetailsJson && env.clusterSecurityGroup) {
                                        def clusterDetails = readJSON text: env.clusterDetailsJson
                                        def targetCluster = clusterDetails.target
                                        def sourceCluster = clusterDetails.source
                                        // Revoke target cluster SG
                                        sh """
                                          if aws ec2 describe-security-groups --group-ids $targetCluster.securityGroupId >/dev/null 2>&1; then
                                            exists=\$(aws ec2 describe-security-groups \
                                              --group-ids $targetCluster.securityGroupId \
                                              --query "SecurityGroups[0].IpPermissions[?UserIdGroupPairs[?GroupId=='$env.clusterSecurityGroup']]" \
                                              --output json)
                                            if [ "\$exists" != "[]" ]; then
                                              echo "CLEANUP: Revoking EKS SG ingress from target cluster SG"
                                              aws ec2 revoke-security-group-ingress \
                                                --group-id $targetCluster.securityGroupId \
                                                --protocol -1 --port -1 \
                                                --source-group $env.clusterSecurityGroup || true
                                            fi
                                          fi
                                        """
                                        // Revoke source cluster SG
                                        sh """
                                          if aws ec2 describe-security-groups --group-ids $sourceCluster.securityGroupId >/dev/null 2>&1; then
                                            exists=\$(aws ec2 describe-security-groups \
                                              --group-ids $sourceCluster.securityGroupId \
                                              --query "SecurityGroups[0].IpPermissions[?UserIdGroupPairs[?GroupId=='$env.clusterSecurityGroup']]" \
                                              --output json)
                                            if [ "\$exists" != "[]" ]; then
                                              echo "CLEANUP: Revoking EKS SG ingress from source cluster SG"
                                              aws ec2 revoke-security-group-ingress \
                                                --group-id $sourceCluster.securityGroupId \
                                                --protocol -1 --port -1 \
                                                --source-group $env.clusterSecurityGroup || true
                                            fi
                                          fi
                                        """
                                    }
                                }

                                // Delete all deployed CloudFormation stacks
                                // Order: MA stack first, then domain stacks, then network stack (VPC dependencies)
                                echo "CLEANUP: Deleting MA stack ${maStackName}"
                                sh "aws cloudformation delete-stack --stack-name ${maStackName} --region ${region} || true"
                                sh "aws cloudformation wait stack-delete-complete --stack-name ${maStackName} --region ${region} || true"

                                echo "CLEANUP: Deleting cluster stack ${clusterStackName}"
                                sh "aws cloudformation delete-stack --stack-name ${clusterStackName} --region ${region} || true"
                                sh "aws cloudformation wait stack-delete-complete --stack-name ${clusterStackName} --region ${region} || true"
                            }
                        }
                    }
                }
            }
        }
    }
}
