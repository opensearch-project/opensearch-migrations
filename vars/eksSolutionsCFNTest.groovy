def call(Map config = [:]) {
    // Config options:
    //   vpcMode: 'create' (default) or 'import'
    //   defaultStage: stage name default
    //   defaultGitUrl: git repo URL default
    //   defaultGitBranch: git branch default
    def vpcMode = config.vpcMode ?: 'create'
    def isImportVpc = (vpcMode == 'import')
    def jobName = config.jobName ?: (isImportVpc ? "eksImportVPCSolutionsCFNTest" : "eksCreateVPCSolutionsCFNTest")

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

            // Deploy a test VPC for Import-VPC mode testing.
            // NOTE: If extending to support additional VPC configurations consider moving to CDK instead of inline CFN.
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

            // Use aws-bootstrap.sh (the production deployment path) which builds
            // minified CFN templates via cdkSynthMinified to stay under the
            // CloudFormation --template-body 51,200 byte limit.
            stage('Deploy & Install') {
                steps {
                    timeout(time: 90, unit: 'MINUTES') {
                        script {
                            def templateName = isImportVpc ? "Migration-Assistant-Infra-Import-VPC-eks" : "Migration-Assistant-Infra-Create-VPC-eks"
                            env.STACK_NAME = "${templateName}-${stage}-${params.REGION}"

                            def bootstrapArgs = isImportVpc ?
                                "--deploy-import-vpc-cfn --vpc-id ${env.TEST_VPC_ID} --subnet-ids ${env.TEST_SUBNET_IDS}" :
                                "--deploy-create-vpc-cfn"
                            def buildImagesArg = params.BUILD_IMAGES ? "--build-images" : ""

                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", region: "${params.REGION}", duration: 7200, roleSessionName: 'jenkins-session') {
                                    sh """
                                        set -euo pipefail
                                        ./deployment/k8s/aws/aws-bootstrap.sh \
                                          ${bootstrapArgs} \
                                          --build-cfn \
                                          --stack-name "${env.STACK_NAME}" \
                                          --stage "${stage}" \
                                          --region "${params.REGION}" \
                                          --version latest \
                                          --skip-console-exec \
                                          ${buildImagesArg}
                                    """
                                }
                            }
                        }
                    }
                }
            }

            stage('Validate EKS Deployment') {
                steps {
                    timeout(time: 15, unit: 'MINUTES') {
                        script {
                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", region: "${params.REGION}", duration: 3600, roleSessionName: 'jenkins-session') {
                                    sh """
                                        set -euo pipefail
                                        kubectl wait --namespace ma --for=condition=ready pod/migration-console-0 --timeout=600s
                                        kubectl exec -n ma migration-console-0 -- /bin/bash -lc 'console --version'
                                    """
                                }
                            }
                            echo "EKS deployment validation completed successfully"
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

// Test VPC template for Import-VPC mode with NAT Gateway for EKS node internet access
def getTestVpcTemplate() {
    return '''{
  "AWSTemplateFormatVersion": "2010-09-09",
  "Description": "Test VPC for Import-VPC EKS CFN testing with NAT Gateway",
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
    "InternetGateway": {
      "Type": "AWS::EC2::InternetGateway",
      "Properties": {
        "Tags": [{"Key": "Name", "Value": {"Fn::Sub": "test-igw-${Stage}"}}]
      }
    },
    "VPCGatewayAttachment": {
      "Type": "AWS::EC2::VPCGatewayAttachment",
      "Properties": {
        "VpcId": {"Ref": "VPC"},
        "InternetGatewayId": {"Ref": "InternetGateway"}
      }
    },
    "PublicSubnet": {
      "Type": "AWS::EC2::Subnet",
      "Properties": {
        "VpcId": {"Ref": "VPC"},
        "CidrBlock": "10.200.0.0/24",
        "AvailabilityZone": {"Fn::Select": [0, {"Fn::GetAZs": ""}]},
        "MapPublicIpOnLaunch": true,
        "Tags": [{"Key": "Name", "Value": {"Fn::Sub": "test-public-subnet-${Stage}"}}]
      }
    },
    "PublicRouteTable": {
      "Type": "AWS::EC2::RouteTable",
      "Properties": {
        "VpcId": {"Ref": "VPC"},
        "Tags": [{"Key": "Name", "Value": {"Fn::Sub": "test-public-rt-${Stage}"}}]
      }
    },
    "PublicRoute": {
      "Type": "AWS::EC2::Route",
      "DependsOn": "VPCGatewayAttachment",
      "Properties": {
        "RouteTableId": {"Ref": "PublicRouteTable"},
        "DestinationCidrBlock": "0.0.0.0/0",
        "GatewayId": {"Ref": "InternetGateway"}
      }
    },
    "PublicSubnetRouteTableAssoc": {
      "Type": "AWS::EC2::SubnetRouteTableAssociation",
      "Properties": {
        "SubnetId": {"Ref": "PublicSubnet"},
        "RouteTableId": {"Ref": "PublicRouteTable"}
      }
    },
    "NatEIP": {
      "Type": "AWS::EC2::EIP",
      "DependsOn": "VPCGatewayAttachment",
      "Properties": {
        "Domain": "vpc",
        "Tags": [{"Key": "Name", "Value": {"Fn::Sub": "test-nat-eip-${Stage}"}}]
      }
    },
    "NatGateway": {
      "Type": "AWS::EC2::NatGateway",
      "Properties": {
        "AllocationId": {"Fn::GetAtt": ["NatEIP", "AllocationId"]},
        "SubnetId": {"Ref": "PublicSubnet"},
        "Tags": [{"Key": "Name", "Value": {"Fn::Sub": "test-nat-${Stage}"}}]
      }
    },
    "PrivateSubnetA": {
      "Type": "AWS::EC2::Subnet",
      "Properties": {
        "VpcId": {"Ref": "VPC"},
        "CidrBlock": "10.200.1.0/24",
        "AvailabilityZone": {"Fn::Select": [0, {"Fn::GetAZs": ""}]},
        "Tags": [{"Key": "Name", "Value": {"Fn::Sub": "test-private-subnet-a-${Stage}"}}]
      }
    },
    "PrivateSubnetB": {
      "Type": "AWS::EC2::Subnet",
      "Properties": {
        "VpcId": {"Ref": "VPC"},
        "CidrBlock": "10.200.2.0/24",
        "AvailabilityZone": {"Fn::Select": [1, {"Fn::GetAZs": ""}]},
        "Tags": [{"Key": "Name", "Value": {"Fn::Sub": "test-private-subnet-b-${Stage}"}}]
      }
    },
    "PrivateRouteTable": {
      "Type": "AWS::EC2::RouteTable",
      "Properties": {
        "VpcId": {"Ref": "VPC"},
        "Tags": [{"Key": "Name", "Value": {"Fn::Sub": "test-private-rt-${Stage}"}}]
      }
    },
    "PrivateRoute": {
      "Type": "AWS::EC2::Route",
      "Properties": {
        "RouteTableId": {"Ref": "PrivateRouteTable"},
        "DestinationCidrBlock": "0.0.0.0/0",
        "NatGatewayId": {"Ref": "NatGateway"}
      }
    },
    "PrivateSubnetARouteTableAssoc": {
      "Type": "AWS::EC2::SubnetRouteTableAssociation",
      "Properties": {
        "SubnetId": {"Ref": "PrivateSubnetA"},
        "RouteTableId": {"Ref": "PrivateRouteTable"}
      }
    },
    "PrivateSubnetBRouteTableAssoc": {
      "Type": "AWS::EC2::SubnetRouteTableAssociation",
      "Properties": {
        "SubnetId": {"Ref": "PrivateSubnetB"},
        "RouteTableId": {"Ref": "PrivateRouteTable"}
      }
    }
  },
  "Outputs": {
    "VpcId": {
      "Value": {"Ref": "VPC"}
    },
    "SubnetIds": {
      "Value": {"Fn::Join": [",", [{"Ref": "PrivateSubnetA"}, {"Ref": "PrivateSubnetB"}]]}
    }
  }
}'''
}
