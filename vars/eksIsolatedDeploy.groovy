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
            string(name: 'ISOLATED_VPC_ID', defaultValue: isolatedVpcId, description: 'VPC ID for isolated deployment')
            string(name: 'ISOLATED_SUBNET_IDS', defaultValue: isolatedSubnetIds, description: 'Comma-separated subnet IDs (isolated, no internet)')
            string(name: 'REGION', defaultValue: region, description: 'AWS region')
        }

        options {
            timeout(time: 4, unit: 'HOURS')
            buildDiscarder(logRotator(daysToKeepStr: '30'))
            skipDefaultCheckout(true)
        }

        stages {
            stage('Checkout') {
                steps {
                    checkoutStep(branch: params.GIT_BRANCH, repo: params.GIT_REPO_URL)
                }
            }

            stage('Build Images') {
                steps {
                    timeout(time: 90, unit: 'MINUTES') {
                        script {
                            sh """
                                ./deployment/k8s/aws/aws-bootstrap.sh \
                                  --deploy-create-vpc-cfn \
                                  --build-cfn \
                                  --build-images \
                                  --build-chart-and-dashboards \
                                  --stack-name "${buildStackName}" \
                                  --stage "${buildStage}" \
                                  --region "${params.REGION}" \
                                  --version latest \
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
                            sh """
                                ./deployment/k8s/aws/aws-bootstrap.sh \
                                  --deploy-import-vpc-cfn \
                                  --build-cfn \
                                  --build-chart-and-dashboards \
                                  --push-all-images-to-private-ecr \
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
