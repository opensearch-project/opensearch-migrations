def call(Map config = [:]) {
    // Config options:
    //   vpcMode: 'Create' (default) or 'Import'
    //   defaultStage: stage name default
    //   defaultGitUrl: git repo URL default
    //   defaultGitBranch: git branch default
    def vpcMode = config.vpcMode ?: 'Create'
    def isImportVpc = (vpcMode == 'Import')

    pipeline {
        agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }

        parameters {
            string(name: 'GIT_REPO_URL', defaultValue: config.defaultGitUrl ?: 'https://github.com/opensearch-project/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: config.defaultGitBranch ?: 'main', description: 'Git branch to use for repository')
            string(name: 'STAGE', defaultValue: config.defaultStage ?: "Eks${vpcMode}Vpc", description: 'Stage name for deployment environment')
            string(name: 'REGION', defaultValue: "us-east-1", description: 'AWS region for deployment')
            booleanParam(name: 'BUILD_IMAGES', defaultValue: false, description: 'Build container images from source instead of using public images')
        }

        options {
            // Acquire lock on a given deployment stage
            lock(label: params.STAGE, quantity: 1, variable: 'stage')
            timeout(time: 3, unit: 'HOURS')
            buildDiscarder(logRotator(daysToKeepStr: '30'))
            skipDefaultCheckout(true)
        }

        environment {
            TEST_VPC_STACK_NAME = "test-vpc-${stage}-${params.REGION}"
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

            stage('Deploy Test VPC') {
                when { expression { isImportVpc } }
                steps {
                    timeout(time: 10, unit: 'MINUTES') {
                        script {
                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", region: "${params.REGION}", duration: 3600, roleSessionName: 'jenkins-session') {
                                    sh """
                                        set -euo pipefail
                                        aws cloudformation create-stack \
                                          --stack-name "${env.TEST_VPC_STACK_NAME}" \
                                          --region "${params.REGION}" \
                                          --template-body '${getTestVpcTemplate()}' \
                                          --parameters ParameterKey=Stage,ParameterValue=${stage}

                                        echo "Waiting for test VPC stack CREATE_COMPLETE..."
                                        aws cloudformation wait stack-create-complete \
                                          --stack-name "${env.TEST_VPC_STACK_NAME}" \
                                          --region "${params.REGION}"
                                    """
                                    env.TEST_VPC_ID = sh(script: "aws cloudformation describe-stacks --stack-name ${env.TEST_VPC_STACK_NAME} --region ${params.REGION} --query 'Stacks[0].Outputs[?OutputKey==`VpcId`].OutputValue' --output text", returnStdout: true).trim()
                                    env.TEST_SUBNET_IDS = sh(script: "aws cloudformation describe-stacks --stack-name ${env.TEST_VPC_STACK_NAME} --region ${params.REGION} --query 'Stacks[0].Outputs[?OutputKey==`SubnetIds`].OutputValue' --output text", returnStdout: true).trim()
                                    echo "Test VPC created: VPC=${env.TEST_VPC_ID}, Subnets=${env.TEST_SUBNET_IDS}"
                                }
                            }
                        }
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
                                def templateName = isImportVpc ? "Migration-Assistant-Infra-Import-VPC-eks" : "Migration-Assistant-Infra-Create-VPC-eks"
                                env.STACK_NAME = "${templateName}-${stage}-${params.REGION}"
                                echo "Deploying CloudFormation stack: ${env.STACK_NAME} in region ${params.REGION}"
                                
                                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", region: "${params.REGION}", duration: 3600, roleSessionName: 'jenkins-session') {
                                        if (isImportVpc) {
                                            sh """
                                                set -euo pipefail
                                                aws cloudformation create-stack \
                                                  --stack-name "${env.STACK_NAME}" \
                                                  --template-body file://cdk.out/${templateName}.template.json \
                                                  --parameters ParameterKey=Stage,ParameterValue=${stage} \
                                                               ParameterKey=VPCId,ParameterValue=${env.TEST_VPC_ID} \
                                                               ParameterKey=VPCSubnetIds,ParameterValue=\\"${env.TEST_SUBNET_IDS}\\" \
                                                  --capabilities CAPABILITY_IAM CAPABILITY_NAMED_IAM \
                                                  --region "${params.REGION}"

                                                echo "Waiting for stack CREATE_COMPLETE..."
                                                aws cloudformation wait stack-create-complete \
                                                  --stack-name "${env.STACK_NAME}" \
                                                  --region "${params.REGION}"

                                                echo "CloudFormation stack ${env.STACK_NAME} is CREATE_COMPLETE."
                                            """
                                        } else {
                                            sh """
                                                set -euo pipefail
                                                aws cloudformation create-stack \
                                                  --stack-name "${env.STACK_NAME}" \
                                                  --template-body file://cdk.out/${templateName}.template.json \
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
                timeout(time: 60, unit: 'MINUTES') {
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

                                // Cleanup test VPC if import-vpc mode
                                if (isImportVpc) {
                                    echo "Cleaning up test VPC stack: ${env.TEST_VPC_STACK_NAME}"
                                    sh """
                                        set -euo pipefail
                                        aws cloudformation delete-stack \
                                            --stack-name "${env.TEST_VPC_STACK_NAME}" \
                                            --region "${params.REGION}"

                                        aws cloudformation wait stack-delete-complete \
                                            --stack-name "${env.TEST_VPC_STACK_NAME}" \
                                            --region "${params.REGION}"

                                        echo "Test VPC stack ${env.TEST_VPC_STACK_NAME} has been deleted."
                                    """
                                }
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

// Test VPC template for Import-VPC mode with VPC endpoints (private, no internet)
def getTestVpcTemplate() {
    return '''{
  "AWSTemplateFormatVersion": "2010-09-09",
  "Description": "Test VPC for Import-VPC EKS CFN testing with private VPC endpoints",
  "Parameters": {
    "Stage": {
      "Type": "String",
      "Default": "test"
    }
  },
  "Resources": {
    "VPC": {
      "Type": "AWS::EC2::VPC",
      "Properties": {
        "CidrBlock": "10.200.0.0/16",
        "EnableDnsHostnames": true,
        "EnableDnsSupport": true,
        "Tags": [{"Key": "Name", "Value": {"Fn::Sub": "test-vpc-${Stage}"}}]
      }
    },
    "SubnetA": {
      "Type": "AWS::EC2::Subnet",
      "Properties": {
        "VpcId": {"Ref": "VPC"},
        "CidrBlock": "10.200.1.0/24",
        "AvailabilityZone": {"Fn::Select": [0, {"Fn::GetAZs": ""}]},
        "Tags": [{"Key": "Name", "Value": {"Fn::Sub": "test-subnet-a-${Stage}"}}]
      }
    },
    "SubnetB": {
      "Type": "AWS::EC2::Subnet",
      "Properties": {
        "VpcId": {"Ref": "VPC"},
        "CidrBlock": "10.200.2.0/24",
        "AvailabilityZone": {"Fn::Select": [1, {"Fn::GetAZs": ""}]},
        "Tags": [{"Key": "Name", "Value": {"Fn::Sub": "test-subnet-b-${Stage}"}}]
      }
    },
    "RouteTable": {
      "Type": "AWS::EC2::RouteTable",
      "Properties": {
        "VpcId": {"Ref": "VPC"},
        "Tags": [{"Key": "Name", "Value": {"Fn::Sub": "test-rt-${Stage}"}}]
      }
    },
    "SubnetARouteTableAssoc": {
      "Type": "AWS::EC2::SubnetRouteTableAssociation",
      "Properties": {
        "SubnetId": {"Ref": "SubnetA"},
        "RouteTableId": {"Ref": "RouteTable"}
      }
    },
    "SubnetBRouteTableAssoc": {
      "Type": "AWS::EC2::SubnetRouteTableAssociation",
      "Properties": {
        "SubnetId": {"Ref": "SubnetB"},
        "RouteTableId": {"Ref": "RouteTable"}
      }
    },
    "EndpointSecurityGroup": {
      "Type": "AWS::EC2::SecurityGroup",
      "Properties": {
        "GroupDescription": "Security group for VPC endpoints",
        "VpcId": {"Ref": "VPC"},
        "SecurityGroupIngress": [
          {"IpProtocol": "tcp", "FromPort": 443, "ToPort": 443, "CidrIp": "10.200.0.0/16"}
        ],
        "Tags": [{"Key": "Name", "Value": {"Fn::Sub": "test-endpoint-sg-${Stage}"}}]
      }
    },
    "S3Endpoint": {
      "Type": "AWS::EC2::VPCEndpoint",
      "Properties": {
        "ServiceName": {"Fn::Sub": "com.amazonaws.${AWS::Region}.s3"},
        "VpcId": {"Ref": "VPC"},
        "VpcEndpointType": "Gateway",
        "RouteTableIds": [{"Ref": "RouteTable"}]
      }
    },
    "EcrApiEndpoint": {
      "Type": "AWS::EC2::VPCEndpoint",
      "Properties": {
        "ServiceName": {"Fn::Sub": "com.amazonaws.${AWS::Region}.ecr.api"},
        "VpcId": {"Ref": "VPC"},
        "VpcEndpointType": "Interface",
        "SubnetIds": [{"Ref": "SubnetA"}, {"Ref": "SubnetB"}],
        "SecurityGroupIds": [{"Ref": "EndpointSecurityGroup"}],
        "PrivateDnsEnabled": true
      }
    },
    "EcrDkrEndpoint": {
      "Type": "AWS::EC2::VPCEndpoint",
      "Properties": {
        "ServiceName": {"Fn::Sub": "com.amazonaws.${AWS::Region}.ecr.dkr"},
        "VpcId": {"Ref": "VPC"},
        "VpcEndpointType": "Interface",
        "SubnetIds": [{"Ref": "SubnetA"}, {"Ref": "SubnetB"}],
        "SecurityGroupIds": [{"Ref": "EndpointSecurityGroup"}],
        "PrivateDnsEnabled": true
      }
    },
    "Ec2Endpoint": {
      "Type": "AWS::EC2::VPCEndpoint",
      "Properties": {
        "ServiceName": {"Fn::Sub": "com.amazonaws.${AWS::Region}.ec2"},
        "VpcId": {"Ref": "VPC"},
        "VpcEndpointType": "Interface",
        "SubnetIds": [{"Ref": "SubnetA"}, {"Ref": "SubnetB"}],
        "SecurityGroupIds": [{"Ref": "EndpointSecurityGroup"}],
        "PrivateDnsEnabled": true
      }
    },
    "EksEndpoint": {
      "Type": "AWS::EC2::VPCEndpoint",
      "Properties": {
        "ServiceName": {"Fn::Sub": "com.amazonaws.${AWS::Region}.eks"},
        "VpcId": {"Ref": "VPC"},
        "VpcEndpointType": "Interface",
        "SubnetIds": [{"Ref": "SubnetA"}, {"Ref": "SubnetB"}],
        "SecurityGroupIds": [{"Ref": "EndpointSecurityGroup"}],
        "PrivateDnsEnabled": true
      }
    },
    "LogsEndpoint": {
      "Type": "AWS::EC2::VPCEndpoint",
      "Properties": {
        "ServiceName": {"Fn::Sub": "com.amazonaws.${AWS::Region}.logs"},
        "VpcId": {"Ref": "VPC"},
        "VpcEndpointType": "Interface",
        "SubnetIds": [{"Ref": "SubnetA"}, {"Ref": "SubnetB"}],
        "SecurityGroupIds": [{"Ref": "EndpointSecurityGroup"}],
        "PrivateDnsEnabled": true
      }
    },
    "StsEndpoint": {
      "Type": "AWS::EC2::VPCEndpoint",
      "Properties": {
        "ServiceName": {"Fn::Sub": "com.amazonaws.${AWS::Region}.sts"},
        "VpcId": {"Ref": "VPC"},
        "VpcEndpointType": "Interface",
        "SubnetIds": [{"Ref": "SubnetA"}, {"Ref": "SubnetB"}],
        "SecurityGroupIds": [{"Ref": "EndpointSecurityGroup"}],
        "PrivateDnsEnabled": true
      }
    }
  },
  "Outputs": {
    "VpcId": {
      "Value": {"Ref": "VPC"}
    },
    "SubnetIds": {
      "Value": {"Fn::Join": [",", [{"Ref": "SubnetA"}, {"Ref": "SubnetB"}]]}
    }
  }
}'''
}
