def call(Map config = [:]) {

    pipeline {
        agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }

        parameters {
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/opensearch-project/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Git branch to use for repository')
            string(name: 'STAGE', defaultValue: "dev", description: 'Stage name for deployment environment')
            string(name: 'REGION', defaultValue: "us-east-1", description: 'AWS region for deployment')
            booleanParam(name: 'BUILD_IMAGES', defaultValue: false, description: 'Build container images from source instead of using public images')
        }

        options {
            // Acquire lock on a given deployment stage
            lock(label: params.STAGE, quantity: 1, variable: 'stage')
            timeout(time: 2, unit: 'HOURS')
            buildDiscarder(logRotator(daysToKeepStr: '30'))
            skipDefaultCheckout(true)
        }

        stages {
            stage('Checkout') {
                steps {
                    script {
                        sh 'sudo chown -R $(whoami) .'
                        sh 'sudo chmod -R u+w .'
                        // If in an existing git repository, remove any additional files in git tree that are not listed in .gitignore
                        if (sh(script: 'git rev-parse --git-dir > /dev/null 2>&1', returnStatus: true) == 0) {
                            echo 'Cleaning any existing git files in workspace'
                            sh 'git reset --hard'
                            sh 'git clean -fd'
                        } else {
                            echo 'No git project detected, this is likely an initial run of this pipeline on the worker'
                        }
                        git branch: "${params.GIT_BRANCH}", url: "${params.GIT_REPO_URL}"
                    }
                }
            }

            stage('Synth EKS CFN Template') {
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
                                env.STACK_NAME = "Migration-Assistant-Infra-Create-VPC-eks-${stage}-${params.REGION}"
                                echo "Deploying CloudFormation stack: ${env.STACK_NAME} in region ${params.REGION}"
                                
                                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", region: "${params.REGION}", duration: 3600, roleSessionName: 'jenkins-session') {
                                        sh """
                                            set -euo pipefail
                                            aws cloudformation create-stack \
                                              --stack-name "${env.STACK_NAME}" \
                                              --template-body file://cdk.out/Migration-Assistant-Infra-Create-VPC-eks.template.json \
                                              --parameters ParameterKey=Stage,ParameterValue=${stage} \
                                              --capabilities CAPABILITY_IAM CAPABILITY_NAMED_IAM \
                                              --region "${params.REGION}"

                                            echo "Waiting for stack CREATE_COMPLETE..."
                                            aws cloudformation wait stack-create-complete \
                                              --stack-name "${env.STACK_NAME}" \
                                              --region "${params.REGION}"

                                            echo "CloudFormation stack ${env.STACK_NAME} is CREATE_COMPLETE."
                                        """
                                    }
                                }
                                echo "CloudFormation stack ${env.STACK_NAME} deployed successfully"
                            }
                        }
                    }
                }
            }

            stage('Install & Validate EKS Deployment') {
                steps {
                    timeout(time: 60, unit: 'MINUTES') {
                        dir('test') {
                            script {
                                echo "Running EKS deployment validation..."
                                echo "Stage: ${stage}"
                                echo "Region: ${params.REGION}"
                                echo "Stack Name: ${env.STACK_NAME}"
                                echo "Build Images: ${params.BUILD_IMAGES}"
                                echo "Branch: ${params.GIT_BRANCH}"
                                
                                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", region: "${params.REGION}", duration: 3600, roleSessionName: 'jenkins-session') {
                                        if (params.BUILD_IMAGES) {
                                            sh """
                                                set -euo pipefail
                                                chmod +x awsRunEksValidation.sh
                                                ./awsRunEksValidation.sh \
                                                  --stage "${stage}" \
                                                  --region "${params.REGION}" \
                                                  --stack-name "${env.STACK_NAME}" \
                                                  --build-images true \
                                                  --org-name opensearch-project \
                                                  --branch "${params.GIT_BRANCH}"
                                            """
                                        } else {
                                            sh """
                                                set -euo pipefail
                                                chmod +x awsRunEksValidation.sh
                                                ./awsRunEksValidation.sh \
                                                  --stage "${stage}" \
                                                  --region "${params.REGION}" \
                                                  --stack-name "${env.STACK_NAME}"
                                            """
                                        }
                                    }
                                }
                                echo "EKS deployment validation completed successfully"
                            }
                        }
                    }
                }
            }
        }
        
        post {
            always {
                timeout(time: 30, unit: 'MINUTES') {
                    script {
                        echo "Cleaning up CloudFormation stack: ${env.STACK_NAME}"
                        withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                            withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", region: "${params.REGION}", duration: 3600, roleSessionName: 'jenkins-session') {
                                echo "Destroying CloudFormation stack ${env.STACK_NAME} in region ${params.REGION}."
                                sh """
                                    set -euo pipefail
                                    aws cloudformation delete-stack \
                                        --stack-name "${env.STACK_NAME}" \
                                        --region "${params.REGION}"

                                    echo "Waiting for stack DELETE_COMPLETE..."
                                    aws cloudformation wait stack-delete-complete \
                                        --stack-name "${env.STACK_NAME}" \
                                        --region "${params.REGION}"

                                    echo "CloudFormation stack ${env.STACK_NAME} has been deleted."
                                """
                            }
                        }
                        echo "CloudFormation cleanup completed"
                        
                        // TODO (MIGRATIONS-2777): Run kubectl with an isolated KUBECONFIG per pipeline run
                        // For now, do best effort cleanup of the migration EKS context created by aws-bootstrap.sh.
                        sh """
                            if command -v kubectl >/dev/null 2>&1; then
                                kubectl config get-contexts 2>/dev/null | grep migration-eks-cluster-${stage}-${params.REGION} | awk '{print \$2}' | xargs -r kubectl config delete-context || echo "No kubectl context to clean up"
                            else
                                echo "kubectl not found on agent; skipping context cleanup"
                            fi
                        """
                    }
                }
            }
        }
    }
}
