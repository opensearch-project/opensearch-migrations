def call(Map config = [:]) {
    // Config options:
    //   vpcMode: 'create' (default) or 'import'
    //   defaultStage: stage name default
    //   defaultGitUrl: git repo URL default
    //   defaultGitBranch: git branch default
    def vpcMode = config.vpcMode ?: 'create'
    def isImportVpc = (vpcMode == 'import')
    def jobName = config.jobName ?: (isImportVpc ? "eksImportVPCSolutionsCFNTest" : "eksCreateVPCSolutionsCFNTest")
    def lockLabel = config.lockLabel ?: (jobName.startsWith("pr-") ? "aws-pr-slot" : "aws-main-slot")

    pipeline {
        agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }

        parameters {
            string(name: 'GIT_REPO_URL', defaultValue: config.defaultGitUrl ?: 'https://github.com/opensearch-project/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: config.defaultGitBranch ?: 'main', description: 'Git branch to use for repository')
            string(name: 'GIT_COMMIT', defaultValue: '', description: '(Optional) Specific commit to checkout after cloning branch')
            string(name: 'STAGE', defaultValue: config.defaultStage ?: (isImportVpc ? "eksivpc" : "ekscvpc"), description: 'Stage name for deployment environment')
            string(name: 'REGION', defaultValue: "us-east-1", description: 'AWS region for deployment')
            booleanParam(name: 'BUILD_IMAGES', defaultValue: true, description: 'Build container images from source instead of using public images')
            booleanParam(name: 'BUILD_CHART_AND_DASHBOARDS', defaultValue: true, description: 'Build Helm chart and dashboards from source instead of using release artifacts')
            booleanParam(name: 'USE_RELEASE_BOOTSTRAP', defaultValue: false, description: 'Download aws-bootstrap.sh from the latest GitHub release instead of using the source checkout version')
            string(name: 'VERSION', defaultValue: 'latest', description: 'Release version to deploy (e.g. "2.8.2" or "latest"). Determines which release artifacts to download for images, chart, and CFN templates.')
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
            stage('Checkout') {
                steps {
                    script {
                        def pool = jobName.startsWith("main-") ? "m" : jobName.startsWith("release-") ? "r" : "p"
                        env.maStageName = "${params.STAGE}-${pool}${currentBuild.number}"
                        env.TEST_VPC_STACK_NAME = "test-vpc-${env.maStageName}-${params.REGION}"
                        echo """
    ================================================================
    EKS Solutions CFN Test (${vpcMode} VPC)
    ================================================================
    Git:                    ${params.GIT_REPO_URL} @ ${params.GIT_BRANCH}
    Stage:                  ${env.maStageName}
    Region:                 ${params.REGION}
    Build Images:           ${params.BUILD_IMAGES}
    Build Chart:            ${params.BUILD_CHART_AND_DASHBOARDS}
    Use Release Bootstrap:  ${params.USE_RELEASE_BOOTSTRAP}
    Version:                ${params.VERSION}
    ================================================================
"""
                    }
                    checkoutStep(branch: params.GIT_BRANCH, repo: params.GIT_REPO_URL, commit: params.GIT_COMMIT)
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
                                          --parameters ParameterKey=Stage,ParameterValue=${maStageName}

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

            // Use the assembled dist/aws-bootstrap.sh (the production deployment path)
            // which is the self-contained script customers actually run.
            // assemble-bootstrap.sh inlines all sourced helpers into a single file.
            stage('Deploy & Install') {
                steps {
                    timeout(time: 90, unit: 'MINUTES') {
                        script {
                            def templateName = isImportVpc ? "Migration-Assistant-Infra-Import-VPC-eks" : "Migration-Assistant-Infra-Create-VPC-eks"
                            env.STACK_NAME = "${templateName}-${maStageName}-${params.REGION}"

                            def bootstrapArgs = isImportVpc ?
                                "--deploy-import-vpc-cfn --vpc-id ${env.TEST_VPC_ID} --subnet-ids ${env.TEST_SUBNET_IDS}" :
                                "--deploy-create-vpc-cfn"

                            def bootstrap = resolveBootstrap(
                                useReleaseBootstrap: params.USE_RELEASE_BOOTSTRAP,
                                buildImages: params.BUILD_IMAGES,
                                buildChartAndDashboards: params.BUILD_CHART_AND_DASHBOARDS,
                                version: params.VERSION
                            )

                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", region: "${params.REGION}", duration: 7200, roleSessionName: 'jenkins-session') {
                                    sh """
                                        set -euo pipefail
                                        ${bootstrap.script} \
                                          ${bootstrapArgs} \
                                          --stack-name "${env.STACK_NAME}" \
                                          --stage "${maStageName}" \
                                          --region "${params.REGION}" \
                                          --skip-console-exec \
                                          --eks-access-principal-arn "arn:aws:iam::\${MIGRATIONS_TEST_ACCOUNT_ID}:role/JenkinsDeploymentRole" \
                                          ${bootstrap.flags}
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
                        def eksClusterName = "migration-eks-cluster-${maStageName}-${params.REGION}"
                        def eksKubeContext = eksClusterName

                        withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                            withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", region: "${params.REGION}", duration: 3600, roleSessionName: 'jenkins-session') {
                                eksCleanupStep(
                                    stackName: env.STACK_NAME,
                                    eksClusterName: eksClusterName,
                                    kubeContext: eksKubeContext
                                )

                                echo "CLEANUP: Deleting MA stack ${env.STACK_NAME}"
                                sh """
                                    aws cloudformation delete-stack \
                                        --stack-name "${env.STACK_NAME}" \
                                        --region "${params.REGION}" || true

                                    aws cloudformation wait stack-delete-complete \
                                        --stack-name "${env.STACK_NAME}" \
                                        --region "${params.REGION}" || true

                                    echo "CloudFormation stack ${env.STACK_NAME} deleted."
                                """

                                // Cleanup test VPC if import-vpc mode
                                if (isImportVpc) {
                                    echo "CLEANUP: Deleting test VPC stack ${env.TEST_VPC_STACK_NAME}"
                                    sh """
                                        aws cloudformation delete-stack \
                                            --stack-name "${env.TEST_VPC_STACK_NAME}" \
                                            --region "${params.REGION}" || true

                                        aws cloudformation wait stack-delete-complete \
                                            --stack-name "${env.TEST_VPC_STACK_NAME}" \
                                            --region "${params.REGION}" || true

                                        echo "Test VPC stack ${env.TEST_VPC_STACK_NAME} deleted."
                                    """
                                }
                            }
                        }
                        echo "CloudFormation cleanup completed"

                        // Clean up the kubeconfig context entry created by aws-bootstrap.sh
                        sh """
                            if command -v kubectl >/dev/null 2>&1; then
                                kubectl config delete-context ${eksKubeContext} 2>/dev/null || echo "No kubectl context to clean up"
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
