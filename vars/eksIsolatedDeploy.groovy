def call(Map config = [:]) {
    def region = config.region ?: 'us-east-2'
    def isolatedVpcId = config.isolatedVpcId ?: ''
    def isolatedSubnetIds = config.isolatedSubnetIds ?: ''
    def isolatedStackName = config.isolatedStackName ?: 'MA-ISOLATED'
    def isolatedStage = config.isolatedStage ?: 'isolated'
    def buildStackName = config.buildStackName ?: "MA-BUILD-${currentBuild.number}"
    def buildStage = config.buildStage ?: "build-${currentBuild.number}"

    pipeline {
        agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }

        parameters {
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/opensearch-project/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Git branch to use')
            string(name: 'GIT_COMMIT', defaultValue: '', description: '(Optional) Specific commit to checkout after cloning branch')
            string(name: 'ISOLATED_VPC_ID', defaultValue: isolatedVpcId, description: 'VPC ID for isolated deployment')
            string(name: 'ISOLATED_SUBNET_IDS', defaultValue: isolatedSubnetIds, description: 'Comma-separated subnet IDs (isolated, no internet)')
            string(name: 'REGION', defaultValue: region, description: 'AWS region')
            booleanParam(name: 'BUILD_IMAGES', defaultValue: true, description: 'Build container images from source instead of using public images')
            booleanParam(name: 'BUILD_CHART_AND_DASHBOARDS', defaultValue: true, description: 'Build Helm chart and dashboards from source instead of using release artifacts')
            booleanParam(name: 'USE_RELEASE_BOOTSTRAP', defaultValue: false, description: 'Download aws-bootstrap.sh from the latest GitHub release instead of using the source checkout version')
            string(name: 'VERSION', defaultValue: 'latest', description: 'Release version for bootstrap artifacts (e.g. 2.8.2). Only used when USE_RELEASE_BOOTSTRAP is true')
        }

        options {
            timeout(time: 4, unit: 'HOURS')
            buildDiscarder(logRotator(daysToKeepStr: '30'))
            skipDefaultCheckout(true)
        }

        stages {
            stage('Checkout') {
                steps {
                    checkoutStep(branch: params.GIT_BRANCH, repo: params.GIT_REPO_URL, commit: params.GIT_COMMIT)
                }
            }

            stage('Build Images') {
                steps {
                    timeout(time: 90, unit: 'MINUTES') {
                        script {
                            def buildImagesArg = params.BUILD_IMAGES ? "--build-images" : ""
                            def buildChartArg = params.BUILD_CHART_AND_DASHBOARDS ? "--build-chart-and-dashboards" : ""
                            def bootstrapPath
                            // Release bootstrap downloads a pre-built bundle that already includes CFN templates,
                            // so --build-cfn is not needed. Source builds must compile CFN from local files.
                            def buildCfnArg
                            def baseDirArg
                            def versionArg
                            if (params.USE_RELEASE_BOOTSTRAP) {
                                sh """
                                    curl -sL -o /tmp/aws-bootstrap.sh \
                                      "https://github.com/opensearch-project/opensearch-migrations/releases/latest/download/aws-bootstrap.sh"
                                    chmod +x /tmp/aws-bootstrap.sh
                                """
                                bootstrapPath = "/tmp/aws-bootstrap.sh"
                                buildCfnArg = ""
                                baseDirArg = ""
                                versionArg = "--version ${params.VERSION}"
                            } else {
                                sh "./deployment/k8s/aws/assemble-bootstrap.sh"
                                bootstrapPath = "./deployment/k8s/aws/dist/aws-bootstrap.sh"
                                buildCfnArg = "--build-cfn"
                                baseDirArg = ""
                                versionArg = "--version latest"
                            }
                            sh """
                                ${bootstrapPath} \
                                  --deploy-create-vpc-cfn \
                                  ${buildCfnArg} \
                                  ${buildImagesArg} \
                                  ${buildChartArg} \
                                  --stack-name "${buildStackName}" \
                                  --stage "${buildStage}" \
                                  --region "${params.REGION}" \
                                  ${versionArg} \
                                  --skip-console-exec \
                                  2>&1 | { set +x; while IFS= read -r line; do printf '%s | %s\\n' "\$(date '+%H:%M:%S')" "\$line"; done; }; exit \${PIPESTATUS[0]}
                            """

                            // Capture the build ECR registry
                            env.BUILD_ECR = sh(
                                script: """
                                    aws cloudformation describe-stacks \\
                                      --stack-name "${buildStackName}" --region "${params.REGION}" \\
                                      --query 'Stacks[0].Outputs[?OutputKey==`MigrationsExportString`].OutputValue' \\
                                      --output text | grep -o 'MIGRATIONS_ECR_REGISTRY=[^;]*' | cut -d= -f2
                                """,
                                returnStdout: true
                            ).trim()
                            echo "Build ECR registry: ${env.BUILD_ECR}"
                        }
                    }
                }
            }

            stage('Deploy to Isolated Network') {
                steps {
                    timeout(time: 90, unit: 'MINUTES') {
                        script {
                            def buildChartArgIsolated = params.BUILD_CHART_AND_DASHBOARDS ? "--build-chart-and-dashboards" : ""
                            def bootstrapPathIsolated
                            def buildCfnArgIsolated
                            if (params.USE_RELEASE_BOOTSTRAP) {
                                bootstrapPathIsolated = "/tmp/aws-bootstrap.sh"
                                buildCfnArgIsolated = ""
                            } else {
                                bootstrapPathIsolated = "./deployment/k8s/aws/dist/aws-bootstrap.sh"
                                buildCfnArgIsolated = "--build-cfn"
                            }
                            sh """
                                ${bootstrapPathIsolated} \
                                  --deploy-import-vpc-cfn \
                                  ${buildCfnArgIsolated} \
                                  ${buildChartArgIsolated} \
                                  --create-vpc-endpoints \
                                  --ma-images-source "${env.BUILD_ECR}" \
                                  --stack-name "${isolatedStackName}" \
                                  --stage "${isolatedStage}" \
                                  --vpc-id "${params.ISOLATED_VPC_ID}" \
                                  --subnet-ids "${params.ISOLATED_SUBNET_IDS}" \
                                  --region "${params.REGION}" \
                                  --skip-console-exec \
                                  2>&1 | { set +x; while IFS= read -r line; do printf '%s | %s\\n' "\$(date '+%H:%M:%S')" "\$line"; done; }; exit \${PIPESTATUS[0]}
                            """
                        }
                    }
                }
            }
        }

        post {
            always {
                script {
                    echo "Cleaning up build stack: ${buildStackName}"
                    sh """
                        aws cloudformation delete-stack --stack-name "${buildStackName}" --region "${params.REGION}" || true
                        aws cloudformation wait stack-delete-complete --stack-name "${buildStackName}" --region "${params.REGION}" || true
                    """
                }
            }
        }
    }
}
