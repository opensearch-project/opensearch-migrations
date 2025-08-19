#!/bin/bash

# AWS Source Cluster Setup Script
# Purpose: Deploy managed OpenSearch Service clusters using AWS Solutions CDK
# Usage: ./awsSourceClusterSetup.sh --cluster-version ES_7.10 --stage dev --region us-west-2

set -e

script_abs_path=$(readlink -f "$0")
TEST_DIR_PATH=$(dirname "$script_abs_path")
TMP_DIR_PATH="$TEST_DIR_PATH/tmp"
AWS_SOLUTIONS_CDK_DIR="$TMP_DIR_PATH/amazon-opensearch-service-sample-cdk"

# Default values
CLUSTER_VERSION=""
STAGE="dev"
REGION="us-west-2"
CLEANUP=false

# Version mapping function
map_version_to_family() {
    case $CLUSTER_VERSION in
        "ES_5.6")
            VERSION_FAMILY="es5x"
            OS_VERSION="5.6"
            CDK_CLUSTER_VERSION="ES_5.6"
            ;;
        "ES_6.8")
            VERSION_FAMILY="es6x"
            OS_VERSION="6.8"
            CDK_CLUSTER_VERSION="ES_6.8"
            ;;
        "ES_7.10")
            VERSION_FAMILY="es7x"
            OS_VERSION="7.10"
            CDK_CLUSTER_VERSION="ES_7.10"
            ;;
        *)
            echo "Error: Unsupported cluster version: $CLUSTER_VERSION"
            echo "Supported versions: ES_5.6, ES_6.8, ES_7.10"
            exit 1
            ;;
    esac
}

# Check and bootstrap CDK in the region if needed
check_and_bootstrap_region() {
    local region=$1
    echo "Checking CDK bootstrap status in region: $region"
    
    # Check if CDKToolkit stack exists
    local bootstrap_status=$(aws cloudformation describe-stacks \
        --stack-name CDKToolkit \
        --region "$region" \
        --query 'Stacks[0].StackStatus' \
        --output text 2>/dev/null)
    
    if [ -z "$bootstrap_status" ] || [ "$bootstrap_status" = "None" ]; then
        echo "CDK not bootstrapped in $region. Bootstrapping now..."
        cdk bootstrap --require-approval never
        if [ $? -ne 0 ]; then
            echo "Error: CDK bootstrap failed"
            exit 1
        fi
        echo "CDK bootstrap completed successfully"
    else
        echo "CDK already bootstrapped in $region (Status: $bootstrap_status)"
    fi
}

# Generate source context for AWS Solutions CDK
generate_source_context() {
    local cluster_id="${VERSION_FAMILY}"
    local domain_name="source-${VERSION_FAMILY}-jenkins-test"
    
    cat > "$TMP_DIR_PATH/sourceContext.json" << EOF
{
  "stage": "${STAGE}",
  "vpcAZCount": 2,
  "clusters": [
    {
      "clusterId": "${cluster_id}",
      "clusterName": "${domain_name}",
      "clusterVersion": "${CDK_CLUSTER_VERSION}",
      "clusterType": "OPENSEARCH_MANAGED_SERVICE",
      "dataNodeCount": 2,
      "dataNodeType": "r6g.large.search",
      "openAccessPolicyEnabled": true,
      "domainRemovalPolicy": "DESTROY",
      "enforceHTTPS": true,
      "nodeToNodeEncryptionEnabled": true,
      "encryptionAtRestEnabled": true,
      "ebsEnabled": true,
      "ebsVolumeSize": 20,
      "ebsVolumeType": "GP3"
    }
  ]
}
EOF
    
    echo "Generated source context for ${domain_name} (${#domain_name} characters)"
    echo "Cluster ID: ${cluster_id}"
}

# Clone AWS Solutions CDK repository
clone_aws_solutions_cdk() {
    echo "Cloning AWS Solutions CDK repository..."
    mkdir -p "$TMP_DIR_PATH"
    
    if [ -d "$AWS_SOLUTIONS_CDK_DIR" ]; then
        echo "Removing existing AWS Solutions CDK directory..."
        rm -rf "$AWS_SOLUTIONS_CDK_DIR"
    fi
    
    git clone https://github.com/aws-samples/amazon-opensearch-service-sample-cdk.git "$AWS_SOLUTIONS_CDK_DIR"
    if [ $? -ne 0 ]; then
        echo "Error: Failed to clone AWS Solutions CDK repository"
        exit 1
    fi
    
    cd "$AWS_SOLUTIONS_CDK_DIR"
    npm install
    if [ $? -ne 0 ]; then
        echo "Error: Failed to install npm dependencies"
        exit 1
    fi
}

# Deploy source cluster
deploy_source_cluster() {
    echo "Deploying source cluster: source-${VERSION_FAMILY}-jenkins-test"
    
    cd "$AWS_SOLUTIONS_CDK_DIR"
    
    # Copy context file to CDK directory
    cp "$TMP_DIR_PATH/sourceContext.json" "./cdk.context.json"
    
    # Deploy the CDK stacks
    cdk deploy "*" --require-approval never --concurrency 3
    if [ $? -ne 0 ]; then
        echo "Error: Failed to deploy source cluster"
        cleanup_temp_directory
        exit 1
    fi
    
    echo "Source cluster deployed successfully"
}

# Extract cluster information from CloudFormation outputs
extract_cluster_info() {
    local cluster_id="${VERSION_FAMILY}"
    local domain_name="source-${VERSION_FAMILY}-jenkins-test"  # Match the domain name used in generate_source_context
    
    # Based on AWS Solutions CDK structure:
    # - OpenSearch Domain Stack: OpenSearchDomain-{clusterId}-{stage}-{region}
    # - Network Stack: NetworkInfra-{stage}-{region}
    local opensearch_stack_name="OpenSearchDomain-${cluster_id}-${STAGE}-${REGION}"
    local network_stack_name="NetworkInfra-${STAGE}-${REGION}"
    
    echo "Extracting cluster information from CloudFormation stacks:"
    echo "  OpenSearch Stack: $opensearch_stack_name"
    echo "  Network Stack: $network_stack_name"
    
    # Get cluster endpoint using CloudFormation export
    # The CDK creates an export: ClusterEndpoint-{stage}-{clusterId}
    local cluster_endpoint=$(aws cloudformation list-exports \
        --region "$REGION" \
        --query "Exports[?Name=='ClusterEndpoint-${STAGE}-${cluster_id}'].Value" \
        --output text 2>/dev/null)
    
    if [ -z "$cluster_endpoint" ] || [ "$cluster_endpoint" = "None" ]; then
        echo "Warning: Could not retrieve cluster endpoint from CloudFormation export, trying stack outputs..."
        # Fallback to stack outputs
        cluster_endpoint=$(aws cloudformation describe-stacks \
            --stack-name "$opensearch_stack_name" \
            --region "$REGION" \
            --query "Stacks[0].Outputs[?contains(OutputKey, 'ClusterEndpoint')].OutputValue" \
            --output text 2>/dev/null)
    fi
    
    if [ -z "$cluster_endpoint" ] || [ "$cluster_endpoint" = "None" ]; then
        echo "Error: Could not retrieve cluster endpoint from CloudFormation"
        echo "Tried export: ClusterEndpoint-${STAGE}-${cluster_id}"
        echo "Tried stack: $opensearch_stack_name"
        exit 1
    fi
    
    # Get VPC ID using CloudFormation export
    # The CDK creates an export: VpcId-{stage}
    local vpc_id=$(aws cloudformation list-exports \
        --region "$REGION" \
        --query "Exports[?Name=='VpcId-${STAGE}'].Value" \
        --output text 2>/dev/null)
    
    if [ -z "$vpc_id" ] || [ "$vpc_id" = "None" ]; then
        echo "Warning: Could not retrieve VPC ID from CloudFormation export, trying stack outputs..."
        # Fallback to stack outputs
        vpc_id=$(aws cloudformation describe-stacks \
            --stack-name "$network_stack_name" \
            --region "$REGION" \
            --query "Stacks[0].Outputs[?contains(OutputKey, 'VpcId')].OutputValue" \
            --output text 2>/dev/null)
    fi
    
    if [ -z "$vpc_id" ] || [ "$vpc_id" = "None" ]; then
        echo "Error: Could not retrieve VPC ID from CloudFormation"
        echo "Tried export: VpcId-${STAGE}"
        echo "Tried stack: $network_stack_name"
        exit 1
    fi
    
    # Output cluster information
    echo "=== SOURCE CLUSTER INFORMATION ==="
    echo "CLUSTER_ENDPOINT=https://$cluster_endpoint"
    echo "CLUSTER_VERSION=$CLUSTER_VERSION"
    echo "VPC_ID=$vpc_id"
    echo "REGION=$REGION"
    echo "DOMAIN_NAME=$domain_name"
    echo "CLUSTER_ID=$cluster_id"
    echo "=================================="
}

# Cleanup temporary directory
cleanup_temp_directory() {
    if [ -d "$TMP_DIR_PATH" ]; then
        echo "Cleaning up temporary directory: $TMP_DIR_PATH"
        rm -rf "$TMP_DIR_PATH"
    fi
}

# Cleanup deployed resources
cleanup_resources() {
    echo "Cleaning up source cluster resources..."
    
    local cluster_id="${VERSION_FAMILY}"
    
    # Based on AWS Solutions CDK structure:
    # - OpenSearch Domain Stack: OpenSearchDomain-{clusterId}-{stage}-{region}
    # - Network Stack: NetworkInfra-{stage}-{region}
    local opensearch_stack_name="OpenSearchDomain-${cluster_id}-${STAGE}-${REGION}"
    local network_stack_name="NetworkInfra-${STAGE}-${REGION}"
    
    echo "Destroying stacks:"
    echo "  OpenSearch Stack: $opensearch_stack_name"
    echo "  Network Stack: $network_stack_name"
    
    # Destroy OpenSearch domain stack first (has dependency on network stack)
    echo "Destroying OpenSearch domain stack: $opensearch_stack_name"
    aws cloudformation delete-stack --stack-name "$opensearch_stack_name" --region "$REGION"
    aws cloudformation wait stack-delete-complete --stack-name "$opensearch_stack_name" --region "$REGION"
    
    # Destroy VPC stack
    echo "Destroying network stack: $network_stack_name"
    aws cloudformation delete-stack --stack-name "$network_stack_name" --region "$REGION"
    aws cloudformation wait stack-delete-complete --stack-name "$network_stack_name" --region "$REGION"
    
    echo "Source cluster cleanup completed"
}

# Usage information
usage() {
    echo ""
    echo "AWS Source Cluster Setup Script"
    echo "Purpose: Deploy managed OpenSearch Service clusters using AWS Solutions CDK"
    echo ""
    echo "Usage:"
    echo "  ./awsSourceClusterSetup.sh --cluster-version ES_7.10 --stage dev --region us-west-2"
    echo ""
    echo "Options:"
    echo "  --cluster-version    Required. Cluster version (ES_5.6, ES_6.8, ES_7.10)"
    echo "  --stage             Stage name for deployment (default: dev)"
    echo "  --region            AWS region (default: us-west-2)"
    echo "  --cleanup           Cleanup deployed resources and exit"
    echo "  -h, --help          Show this help message"
    echo ""
    echo "Examples:"
    echo "  ./awsSourceClusterSetup.sh --cluster-version ES_7.10 --stage test --region us-east-1"
    echo "  ./awsSourceClusterSetup.sh --cleanup --cluster-version ES_7.10 --stage test"
    echo ""
    exit 1
}

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --cluster-version)
            CLUSTER_VERSION="$2"
            shift 2
            ;;
        --stage)
            STAGE="$2"
            shift 2
            ;;
        --region)
            REGION="$2"
            shift 2
            ;;
        --cleanup)
            CLEANUP=true
            shift
            ;;
        -h|--help)
            usage
            ;;
        *)
            echo "Unknown option: $1"
            usage
            ;;
    esac
done

# Validate required parameters
if [ -z "$CLUSTER_VERSION" ]; then
    echo "Error: --cluster-version is required"
    usage
fi

# Map version to family
map_version_to_family

# Set AWS region
export AWS_DEFAULT_REGION="$REGION"

# Main execution
if [ "$CLEANUP" = true ]; then
    cleanup_resources
    exit 0
fi

echo "Starting AWS Source Cluster Setup..."
echo "Cluster Version: $CLUSTER_VERSION"
echo "Version Family: $VERSION_FAMILY"
echo "Stage: $STAGE"
echo "Region: $REGION"

# Execute deployment steps
clone_aws_solutions_cdk
generate_source_context
check_and_bootstrap_region "$REGION"
deploy_source_cluster
extract_cluster_info
cleanup_temp_directory

echo "AWS Source Cluster Setup completed successfully!"
