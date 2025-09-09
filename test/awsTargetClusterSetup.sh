#!/bin/bash

# AWS Target Cluster Setup Script
# Purpose: Deploy target OpenSearch clusters for snapshot-based migration testing
# This script focuses solely on target cluster deployment and information extraction

# Script paths and directories
SCRIPT_ABS_PATH=$(readlink -f "$0")
TEST_DIR_PATH=$(dirname "$SCRIPT_ABS_PATH")
TMP_DIR_PATH="$TEST_DIR_PATH/tmp"
AWS_SOLUTIONS_CDK_DIR="$TMP_DIR_PATH/amazon-opensearch-service-sample-cdk"

# Default values
CLUSTER_VERSION=""
STAGE="rfs-metrics"
REGION="us-west-2"
CLEANUP=false
TARGET_DATA_NODE_TYPE="r6g.4xlarge.search"
TARGET_DATA_NODE_COUNT="10"
TARGET_MANAGER_NODE_TYPE="m6g.xlarge.search"
TARGET_MANAGER_NODE_COUNT="4"
TARGET_EBS_ENABLED="true"

# Create required service-linked roles
create_service_linked_roles() {
    echo "Creating required service-linked roles..."
    aws iam create-service-linked-role --aws-service-name opensearchservice.amazonaws.com 2>/dev/null || echo "OpenSearch service-linked role already exists"
    aws iam create-service-linked-role --aws-service-name ecs.amazonaws.com 2>/dev/null || echo "ECS service-linked role already exists"
    aws iam create-service-linked-role --aws-service-name osis.amazonaws.com 2>/dev/null || echo "OSIS service-linked role already exists"
    echo "Service-linked roles creation completed"
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

# Generate target context for AWS Solutions CDK
generate_target_context() {
    local cluster_id="target-os2x"
    local domain_name="target-os2x-${STAGE}"
    
    echo "Generating target context for AWS Solutions CDK..."
    echo "Cluster ID: ${cluster_id}"
    echo "Domain Name: ${domain_name}"
    echo "Stage: ${STAGE}"
    echo "Region: ${REGION}"
    echo "Target Configuration:"
    echo "  - Data Nodes: ${TARGET_DATA_NODE_COUNT} x ${TARGET_DATA_NODE_TYPE}"
    echo "  - Manager Nodes: ${TARGET_MANAGER_NODE_COUNT} x ${TARGET_MANAGER_NODE_TYPE}"
    echo "  - EBS Enabled: ${TARGET_EBS_ENABLED}"
    
    mkdir -p "$TMP_DIR_PATH"
    
    # Write the context in the schema the sample CDK expects using parameters
    cat > "$TMP_DIR_PATH/targetContext.json" << EOF
{
  "stage": "${STAGE}",
  "region": "${REGION}",
  "vpcAZCount": 2,
  "clusters": [
    {
      "clusterId": "${cluster_id}",
      "clusterType": "OPENSEARCH_MANAGED_SERVICE",
      "clusterVersion": "${CDK_CLUSTER_VERSION}",
      "domainName": "${domain_name}",
      "dataNodeType": "${TARGET_DATA_NODE_TYPE}",
      "dataNodeCount": ${TARGET_DATA_NODE_COUNT},
      "dedicatedManagerNodeType": "${TARGET_MANAGER_NODE_TYPE}",
      "dedicatedManagerNodeCount": ${TARGET_MANAGER_NODE_COUNT},
      "ebsEnabled": ${TARGET_EBS_ENABLED},
      "ebsVolumeSize": 100,
      "ebsVolumeType": "GP3",
      "useUnsignedBasicAuth": false,
      "enforceHTTPS": true,
      "tlsSecurityPolicy": "TLS_1_2",
      "encryptionAtRestEnabled": true,
      "nodeToNodeEncryptionEnabled": true,
      "openAccessPolicyEnabled": true
    }
  ]
}
EOF
    
    echo "Target context file created successfully"
    echo "Context includes:"
    echo "  - Stage: ${STAGE}"
    echo "  - Cluster ID: ${cluster_id}"
    echo "  - Cluster Type: OPENSEARCH_MANAGED_SERVICE"
    echo "  - Data Nodes: ${TARGET_DATA_NODE_COUNT} x ${TARGET_DATA_NODE_TYPE}"
    echo "  - Manager Nodes: ${TARGET_MANAGER_NODE_COUNT} x ${TARGET_MANAGER_NODE_TYPE}"
    echo "  - EBS Enabled: ${TARGET_EBS_ENABLED}"
    echo "  - EBS Volume Type: GP3"
    echo "  - OpenSearch Version: ${OS_VERSION}"
    echo "  - VPC AZ Count: 2"
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
    
    cd "$TEST_DIR_PATH"
}

# Deploy target cluster
deploy_target_cluster() {
    echo "Deploying target cluster..."
    
    cd "$AWS_SOLUTIONS_CDK_DIR"
    
    # Verify target context file exists before copying
    if [ ! -f "$TMP_DIR_PATH/targetContext.json" ]; then
        echo "Error: Target context file not found at $TMP_DIR_PATH/targetContext.json"
        echo "Expected file to be created by generate_target_context function"
        cleanup_temp_directory
        exit 1
    fi
    
    # Copy context file to CDK directory
    echo "Copying target context to CDK directory..."
    cp "$TMP_DIR_PATH/targetContext.json" "./cdk.context.json"
    
    # Verify the copy was successful
    if [ ! -f "./cdk.context.json" ]; then
        echo "Error: Failed to copy target context to CDK directory"
        cleanup_temp_directory
        exit 1
    fi
    
    echo "Target context copied successfully"
    
    # Deploy the CDK stacks
    echo "Deploying CDK stacks for target cluster..."
    cdk deploy "*" --require-approval never --concurrency 3
    if [ $? -ne 0 ]; then
        echo "Error: Failed to deploy target cluster"
        cleanup_temp_directory
        exit 1
    fi
    
    echo "Target cluster deployed successfully"
    cd "$TEST_DIR_PATH"
}

# Extract cluster information from CloudFormation outputs
extract_cluster_info() {
    local cluster_id="target-os2x"
    local domain_name="target-os2x-${STAGE}"
    
    # Based on AWS Solutions CDK structure and your actual stack names:
    # - OpenSearch Domain Stack: OpenSearchDomain-{clusterId}-{stage}-{region}
    # - Network Stack: NetworkInfra-{stage}-{region}
    local opensearch_stack_name="OpenSearchDomain-${cluster_id}-${STAGE}-${REGION}"
    local network_stack_name="NetworkInfra-${STAGE}-${REGION}"
    
    echo "Extracting cluster information from CloudFormation stacks:"
    echo "  OpenSearch Stack: $opensearch_stack_name"
    echo "  Network Stack: $network_stack_name"
    
    # Debug: List all available exports to understand the naming pattern
    echo "DEBUG: Listing all CloudFormation exports to identify correct naming pattern..."
    aws cloudformation list-exports --region "$REGION" --query "Exports[].Name" --output table 2>/dev/null || echo "Could not list exports"
    
    # Get cluster endpoint using CloudFormation exports (based on AWS Solutions CDK actual export pattern)
    # The actual pattern from generateClusterExports is: ClusterEndpoint-${stage}-${clusterId}
    echo "DEBUG: Looking for export with pattern: ClusterEndpoint-${STAGE}-${cluster_id}"
    
    local cluster_endpoint=$(aws cloudformation list-exports \
        --region "$REGION" \
        --query "Exports[?Name=='ClusterEndpoint-${STAGE}-${cluster_id}'].Value" \
        --output text 2>/dev/null)
    
    if [ -z "$cluster_endpoint" ] || [ "$cluster_endpoint" = "None" ]; then
        echo "Warning: Could not retrieve cluster endpoint from CloudFormation export, trying alternative patterns..."
        
        local alt_patterns=(
            "ClusterEndpoint-${STAGE}-${cluster_id}"
            "ClusterEndpointExport-${STAGE}-${cluster_id}"
            "ClusterEndpoint${STAGE}${cluster_id}"
            "DomainEndpoint-${STAGE}-${cluster_id}"
        )
        
        for pattern in "${alt_patterns[@]}"; do
            echo "DEBUG: Trying export pattern: $pattern"
            cluster_endpoint=$(aws cloudformation list-exports \
                --region "$REGION" \
                --query "Exports[?Name=='$pattern'].Value" \
                --output text 2>/dev/null)
            
            if [ -n "$cluster_endpoint" ] && [ "$cluster_endpoint" != "None" ]; then
                echo "SUCCESS: Found cluster endpoint using pattern: $pattern"
                break
            fi
        done
    fi
    
    if [ -z "$cluster_endpoint" ] || [ "$cluster_endpoint" = "None" ]; then
        echo "Warning: Could not retrieve cluster endpoint from CloudFormation export, trying stack outputs..."
        
        # Debug: List stack outputs to understand what's available
        echo "DEBUG: Listing stack outputs for $opensearch_stack_name..."
        aws cloudformation describe-stacks \
            --stack-name "$opensearch_stack_name" \
            --region "$REGION" \
            --query "Stacks[0].Outputs[].[OutputKey,OutputValue]" \
            --output table 2>/dev/null || echo "Could not describe stack outputs"
        
        # Fallback to stack outputs with broader search patterns
        cluster_endpoint=$(aws cloudformation describe-stacks \
            --stack-name "$opensearch_stack_name" \
            --region "$REGION" \
            --query "Stacks[0].Outputs[?contains(OutputKey, 'ClusterEndpoint') || contains(OutputKey, 'domainEndpoint') || contains(OutputKey, 'DomainEndpoint') || contains(OutputKey, 'Endpoint')].OutputValue" \
            --output text 2>/dev/null)
    fi
    
    if [ -z "$cluster_endpoint" ] || [ "$cluster_endpoint" = "None" ]; then
        echo "Error: Could not retrieve cluster endpoint from CloudFormation"
        echo "Tried export: ClusterEndpointExport${stage_no_hyphens}${cluster_id_no_hyphens}"
        echo "Tried stack: $opensearch_stack_name"
        echo "Please check the CloudFormation console for available exports and outputs"
        exit 1
    fi
    
    # Get VPC ID using CloudFormation stack outputs
    echo "DEBUG: Looking for VPC ID from network stack outputs..."
    local vpc_id=$(aws cloudformation describe-stacks \
        --stack-name "$network_stack_name" \
        --region "$REGION" \
        --query "Stacks[0].Outputs[?contains(OutputKey, 'VpcId')].OutputValue" \
        --output text 2>/dev/null)
    
    if [ -z "$vpc_id" ] || [ "$vpc_id" = "None" ]; then
        echo "Warning: Could not retrieve VPC ID using VpcId key, trying alternative patterns..."
        # Try alternative patterns for VPC ID
        vpc_id=$(aws cloudformation describe-stacks \
            --stack-name "$network_stack_name" \
            --region "$REGION" \
            --query "Stacks[0].Outputs[?contains(OutputKey, 'Vpc')].OutputValue" \
            --output text 2>/dev/null)
    fi
    
    if [ -z "$vpc_id" ] || [ "$vpc_id" = "None" ]; then
        echo "Warning: Could not retrieve VPC ID from OutputKey patterns, trying OutputValue search..."
        # Last resort: search by output value pattern and extract only VPC ID
        local raw_output=$(aws cloudformation describe-stacks \
            --stack-name "$network_stack_name" \
            --region "$REGION" \
            --query "Stacks[0].Outputs[?contains(OutputValue, 'vpc-')].OutputValue" \
            --output text 2>/dev/null)
        
        # Extract only the VPC ID from potentially mixed output
        vpc_id=$(echo "$raw_output" | grep -o 'vpc-[a-z0-9]*' | head -1)
    fi
    
    # Validate VPC ID format and ensure it's a single VPC ID
    if [ -z "$vpc_id" ] || [ "$vpc_id" = "None" ]; then
        echo "Error: Could not retrieve VPC ID from CloudFormation"
        echo "Tried stack: $network_stack_name"
        echo "DEBUG: Listing all stack outputs for debugging..."
        aws cloudformation describe-stacks \
            --stack-name "$network_stack_name" \
            --region "$REGION" \
            --query "Stacks[0].Outputs[].[OutputKey,OutputValue]" \
            --output table 2>/dev/null || echo "Could not describe network stack outputs"
        exit 1
    fi
    
    # Ensure we have a valid VPC ID format (vpc-xxxxxxxxx)
    if ! [[ "$vpc_id" =~ ^vpc-[a-z0-9]+$ ]]; then
        echo "Warning: VPC ID format validation failed. Got: '$vpc_id'"
        # Try to extract a valid VPC ID from the output
        local clean_vpc_id=$(echo "$vpc_id" | grep -o 'vpc-[a-z0-9]*' | head -1)
        if [ -n "$clean_vpc_id" ]; then
            echo "Extracted valid VPC ID: $clean_vpc_id"
            vpc_id="$clean_vpc_id"
        else
            echo "Error: Could not extract a valid VPC ID from: '$vpc_id'"
            exit 1
        fi
    fi
    
    echo "Successfully extracted VPC ID: $vpc_id"
    
    # Get OpenSearch domain security group ID
    local opensearch_sg_id=$(aws cloudformation list-exports \
        --region "$REGION" \
        --query "Exports[?Name=='ClusterAccessSecurityGroupIdExport${stage_no_hyphens}${cluster_id_no_hyphens}'].Value" \
        --output text 2>/dev/null)
    
    if [ -z "$opensearch_sg_id" ] || [ "$opensearch_sg_id" = "None" ]; then
        echo "Warning: Could not retrieve security group ID from CloudFormation export, trying stack outputs..."
        # Fallback to stack outputs
        opensearch_sg_id=$(aws cloudformation describe-stacks \
            --stack-name "$opensearch_stack_name" \
            --region "$REGION" \
            --query "Stacks[0].Outputs[?contains(OutputKey, 'SecurityGroup')].OutputValue" \
            --output text 2>/dev/null)
    fi
    
    if [ -z "$opensearch_sg_id" ] || [ "$opensearch_sg_id" = "None" ]; then
        echo "Warning: Could not retrieve OpenSearch security group ID from CloudFormation"
        echo "Migration infrastructure will need to discover it dynamically"
    else
        echo "Found OpenSearch Security Group ID: $opensearch_sg_id"
    fi
    
    # Validate extracted information
    if [ -z "$cluster_endpoint" ] || [ -z "$vpc_id" ]; then
        echo "Error: Failed to extract required cluster information"
        exit 1
    fi
    
    echo "Successfully extracted target cluster information"
    
    # Output structured information for pipeline parsing (matching working reference pattern)
    echo "=== TARGET CLUSTER INFORMATION ==="
    echo "CLUSTER_ENDPOINT=https://$cluster_endpoint"
    echo "VPC_ID=$vpc_id"
    echo "DOMAIN_NAME=$domain_name"
    echo "CLUSTER_ID=$cluster_id"
    if [ -n "$opensearch_sg_id" ] && [ "$opensearch_sg_id" != "None" ]; then
        echo "SECURITY_GROUP_ID=$opensearch_sg_id"
    fi
    echo "REGION=$REGION"
    echo "STAGE=$STAGE"
    echo "=================================="
}

# Cleanup deployed resources
cleanup_resources() {
    echo "Cleaning up target cluster resources..."

    local cluster_id="target-os2x"

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

    # Destroy network stack
    echo "Destroying network stack: $network_stack_name"
    aws cloudformation delete-stack --stack-name "$network_stack_name" --region "$REGION"
    aws cloudformation wait stack-delete-complete --stack-name "$network_stack_name" --region "$REGION"

    echo "Target cluster cleanup completed"
}

# Cleanup temporary directory
cleanup_temp_directory() {
    if [ -d "$TMP_DIR_PATH" ]; then
        echo "Cleaning up temporary directory: $TMP_DIR_PATH"
        rm -rf "$TMP_DIR_PATH"
    fi
}

# Usage information
usage() {
    echo ""
    echo "AWS Target Cluster Setup Script"
    echo "Purpose: Deploy target OpenSearch Service clusters using AWS Solutions CDK"
    echo ""
    echo "Usage:"
    echo "  ./awsTargetClusterSetup.sh --cluster-version OS_2.19 --stage rfs-metrics --region us-west-2"
    echo ""
    echo "Options:"
    echo "  --cluster-version           Required. Cluster version (ES_6.8, ES_7.10, OS_1.3, OS_2.19)"
    echo "  --stage                     Stage name for deployment (default: rfs-metrics)"
    echo "  --region                    AWS region (default: us-west-2)"
    echo "  --target-data-node-type     Data node instance type (default: r6g.4xlarge.search)"
    echo "  --target-data-node-count    Number of data nodes (default: 10)"
    echo "  --target-manager-node-type  Manager node instance type (default: m6g.xlarge.search)"
    echo "  --target-manager-node-count Number of manager nodes (default: 4)"
    echo "  --target-ebs-enabled        Enable EBS storage (default: true)"
    echo "  --cleanup                   Cleanup deployed resources and exit"
    echo "  -h, --help                  Show this help message"
    echo ""
    echo "Examples:"
    echo "  ./awsTargetClusterSetup.sh --cluster-version OS_2.19 --stage rfs-metrics --region us-west-2"
    echo "  ./awsTargetClusterSetup.sh --cluster-version OS_2.19 --target-data-node-count 20 --target-data-node-type r6g.8xlarge.search"
    echo "  ./awsTargetClusterSetup.sh --cleanup --cluster-version OS_2.19 --stage rfs-metrics"
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
        --target-data-node-type)
            TARGET_DATA_NODE_TYPE="$2"
            shift 2
            ;;
        --target-data-node-count)
            TARGET_DATA_NODE_COUNT="$2"
            shift 2
            ;;
        --target-manager-node-type)
            TARGET_MANAGER_NODE_TYPE="$2"
            shift 2
            ;;
        --target-manager-node-count)
            TARGET_MANAGER_NODE_COUNT="$2"
            shift 2
            ;;
        --target-ebs-enabled)
            TARGET_EBS_ENABLED="$2"
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

# Validate target cluster configuration parameters
if ! [[ "$TARGET_DATA_NODE_COUNT" =~ ^[0-9]+$ ]] || [ "$TARGET_DATA_NODE_COUNT" -lt 1 ]; then
    echo "Error: --target-data-node-count must be a positive integer, got: $TARGET_DATA_NODE_COUNT"
    usage
fi

if ! [[ "$TARGET_MANAGER_NODE_COUNT" =~ ^[0-9]+$ ]] || [ "$TARGET_MANAGER_NODE_COUNT" -lt 0 ]; then
    echo "Error: --target-manager-node-count must be a non-negative integer, got: $TARGET_MANAGER_NODE_COUNT"
    usage
fi

if [[ "$TARGET_EBS_ENABLED" != "true" && "$TARGET_EBS_ENABLED" != "false" ]]; then
    echo "Error: --target-ebs-enabled must be 'true' or 'false', got: $TARGET_EBS_ENABLED"
    usage
fi

# Validate instance types (basic format check)
if ! [[ "$TARGET_DATA_NODE_TYPE" =~ ^[a-z0-9]+\.[a-z0-9]+\.search$ ]]; then
    echo "Error: --target-data-node-type must be a valid OpenSearch instance type (e.g., r6g.4xlarge.search), got: $TARGET_DATA_NODE_TYPE"
    usage
fi

if ! [[ "$TARGET_MANAGER_NODE_TYPE" =~ ^[a-z0-9]+\.[a-z0-9]+\.search$ ]]; then
    echo "Error: --target-manager-node-type must be a valid OpenSearch instance type (e.g., m6g.xlarge.search), got: $TARGET_MANAGER_NODE_TYPE"
    usage
fi

# Set version family variables for OS_2.19
VERSION_FAMILY="os2x"
OS_VERSION="2.19"
CDK_CLUSTER_VERSION="OS_2.19"

# Set AWS region
export AWS_DEFAULT_REGION="$REGION"

# Main execution
if [ "$CLEANUP" = true ]; then
    cleanup_resources
    cleanup_temp_directory
    exit 0
fi

echo "Starting AWS Target Cluster Setup..."
echo "Cluster Version: $CLUSTER_VERSION"
echo "Stage: $STAGE"
echo "Region: $REGION"
echo "Target Cluster Configuration:"
echo "  - Data Nodes: $TARGET_DATA_NODE_COUNT x $TARGET_DATA_NODE_TYPE"
echo "  - Manager Nodes: $TARGET_MANAGER_NODE_COUNT x $TARGET_MANAGER_NODE_TYPE"
echo "  - EBS Enabled: $TARGET_EBS_ENABLED"

# Execute deployment pipeline
create_service_linked_roles
clone_aws_solutions_cdk
generate_target_context
check_and_bootstrap_region "$REGION"
deploy_target_cluster
extract_cluster_info
cleanup_temp_directory

echo "AWS Target Cluster Setup completed successfully!"
