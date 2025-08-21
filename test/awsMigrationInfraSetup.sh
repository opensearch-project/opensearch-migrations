#!/bin/bash

set -e

SCRIPT_ABS_PATH=$(readlink -f "$0")
TEST_DIR_PATH=$(dirname "$SCRIPT_ABS_PATH")
ROOT_REPO_PATH=$(dirname "$TEST_DIR_PATH")
MIGRATION_CDK_PATH="$ROOT_REPO_PATH/deployment/cdk/opensearch-service-migration"
TMP_DIR_PATH="$TEST_DIR_PATH/tmp"

SOURCE_ENDPOINT=""
SOURCE_VERSION=""
VPC_ID=""
STAGE="dev"
REGION="us-west-2"
CLEANUP=false

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

# Version mapping function
map_version_to_family() {
    case $SOURCE_VERSION in
        "ES_5.6")
            VERSION_FAMILY="es5x"
            ;;
        "ES_6.8")
            VERSION_FAMILY="es6x"
            ;;
        "ES_7.10")
            VERSION_FAMILY="es7x"
            ;;
        "OS_1.3")
            VERSION_FAMILY="os1x"
            ;;
        "OS_2.19")
            VERSION_FAMILY="os2x"
            ;;
        *)
            echo "Error: Unsupported source version: $SOURCE_VERSION"
            echo "Supported versions: ES_5.6, ES_6.8, ES_7.10, OS_1.3, OS_2.19"
            exit 1
            ;;
    esac
}

# Generate migration context with dynamic values
generate_migration_context() {
    local context_file="$TMP_DIR_PATH/migrationContext.json"
    
    mkdir -p "$TMP_DIR_PATH"
    
    cat > "$context_file" << EOF
{
  "default": {
    "stage": "${STAGE}",
    "targetCluster": {
      "endpoint": "${SOURCE_ENDPOINT}",
      "allow_insecure": true,
      "auth": {
        "type": "sigv4",
        "region": "${REGION}",
        "serviceSigningName": "es"
      }
    },
    "sourceCluster": {
      "endpoint": "${SOURCE_ENDPOINT}",
      "allow_insecure": true,
      "version": "${SOURCE_VERSION}",
      "auth": {
        "type": "sigv4",
        "region": "${REGION}",
        "serviceSigningName": "es"
      }
    },
    "MskEbsStorage": {
      "maxCapacity": 16384
    },
    "vpcId": "${VPC_ID}",
    "vpcAZCount": 2,
    "reindexFromSnapshotServiceEnabled": true,
    "reindexFromSnapshotExtraArgs": "--doc-transformer-config-file /shared-logs-output/test-transformations/transformation.json",
    "reindexFromSnapshotMaxShardSizeGiB": 80,
    "artifactBucketRemovalPolicy": "DESTROY",
    "managedServiceSourceSnapshotEnabled": true,
    "otelCollectorEnabled": true,
    "migrationConsoleServiceEnabled": true,
    "trafficReplayerServiceEnabled": false,
    "captureProxyServiceEnabled": false,
    "targetClusterProxyServiceEnabled": false,
    "captureProxyDesiredCount": 0,
    "targetClusterProxyDesiredCount": 0,
    "trafficReplayerExtraArgs": "--speedup-factor 1.5"
  }
}
EOF
    
    echo "Generated migration context: $context_file"
    echo "Source/Target Endpoint: $SOURCE_ENDPOINT"
    echo "Source Version: $SOURCE_VERSION"
    echo "VPC ID: $VPC_ID"
}

# Build Docker images for migration services
build_docker_images() {
    echo "Building Docker images for migration services..."
    
    cd "$MIGRATION_CDK_PATH"
    
    ./buildDockerImages.sh
    if [ $? -ne 0 ]; then
        echo "Error: Failed to build Docker images"
        exit 1
    fi
    
    echo "Docker images built successfully"
}

# Install CDK dependencies
install_cdk_dependencies() {
    echo "Installing CDK dependencies..."
    
    cd "$MIGRATION_CDK_PATH"
    
    npm install
    if [ $? -ne 0 ]; then
        echo "Error: Failed to install CDK dependencies"
        exit 1
    fi
    
    echo "CDK dependencies installed successfully"
}

# Deploy migration infrastructure
deploy_migration_infrastructure() {
    echo "Deploying migration infrastructure..."
    
    cd "$MIGRATION_CDK_PATH"
    
    # Deploy CDK stacks with generated context
    cdk deploy "*" \
        --c contextFile="$TMP_DIR_PATH/migrationContext.json" \
        --c contextId="default" \
        --require-approval never \
        --concurrency 3
    
    if [ $? -ne 0 ]; then
        echo "Error: Failed to deploy migration infrastructure"
        exit 1
    fi
    
    echo "Migration infrastructure deployed successfully"
}

# Configure security groups for OpenSearch Service connectivity
configure_source_cluster_security_groups() {
    echo "Configuring security groups for source cluster connectivity..."
    
    # Get migration console security group ID from SSM (stored by migration CDK)
    local migration_sg_id=$(aws ssm get-parameter \
        --name "/migration/${STAGE}/default/serviceSecurityGroupId" \
        --query 'Parameter.Value' \
        --output text \
        --region "$REGION" 2>/dev/null)
    
    if [ -z "$migration_sg_id" ] || [ "$migration_sg_id" = "None" ]; then
        echo "Warning: Could not retrieve migration console security group ID from SSM"
        echo "Trying to find it from CloudFormation stacks..."
        
        # Try to get it from migration infrastructure stack outputs
        migration_sg_id=$(aws cloudformation describe-stacks \
            --stack-name "migration-${STAGE}-migration-infrastructure" \
            --region "$REGION" \
            --query "Stacks[0].Outputs[?contains(OutputKey, 'ServiceSecurityGroup')].OutputValue" \
            --output text 2>/dev/null)
    fi
    
    if [ -z "$migration_sg_id" ] || [ "$migration_sg_id" = "None" ]; then
        echo "Warning: Could not find migration console security group ID"
        echo "Security group integration will be skipped"
        return 1
    fi
    
    echo "Found migration console security group: $migration_sg_id"
    
    # Discover OpenSearch Service security group dynamically in the VPC
    echo "Discovering OpenSearch Service security group in VPC: ${VPC_ID}..."
    
    # Try to find OpenSearch Service security groups in the VPC
    local source_sg_id=$(aws ec2 describe-security-groups \
        --filters "Name=vpc-id,Values=${VPC_ID}" "Name=group-name,Values=*opensearch*" \
        --query 'SecurityGroups[0].GroupId' \
        --output text \
        --region "$REGION" 2>/dev/null)
    
    if [ -z "$source_sg_id" ] || [ "$source_sg_id" = "None" ]; then
        # Try alternative naming patterns
        source_sg_id=$(aws ec2 describe-security-groups \
            --filters "Name=vpc-id,Values=${VPC_ID}" "Name=group-name,Values=*cluster*" \
            --query 'SecurityGroups[0].GroupId' \
            --output text \
            --region "$REGION" 2>/dev/null)
    fi
    
    if [ -z "$source_sg_id" ] || [ "$source_sg_id" = "None" ]; then
        echo "Warning: Could not find source cluster security group ID"
        echo "Manual security group configuration may be required"
        return 1
    fi
    
    echo "Found source cluster security group: $source_sg_id"
    
    # Add inbound rule to source cluster security group using VPC CIDR (no cross-reference)
    echo "Adding VPC CIDR-based security group rule to allow migration console access..."
    
    # Get VPC CIDR block dynamically
    local vpc_cidr=$(aws ec2 describe-vpcs \
        --vpc-ids "$VPC_ID" \
        --query 'Vpcs[0].CidrBlock' \
        --output text \
        --region "$REGION" 2>/dev/null)
    
    if [ -z "$vpc_cidr" ] || [ "$vpc_cidr" = "None" ]; then
        echo "Warning: Could not retrieve VPC CIDR block for VPC: $VPC_ID"
        echo "Skipping security group rule creation"
        return 1
    fi
    
    echo "Found VPC CIDR block: $vpc_cidr"
    
    # Check if CIDR-based rule already exists
    local existing_rule=$(aws ec2 describe-security-groups \
        --group-ids "$source_sg_id" \
        --query "SecurityGroups[0].IpPermissions[?FromPort==\`443\` && ToPort==\`443\` && IpRanges[?CidrIp==\`${vpc_cidr}\`]]" \
        --output text \
        --region "$REGION" 2>/dev/null)
    
    if [ -n "$existing_rule" ] && [ "$existing_rule" != "None" ]; then
        echo "VPC CIDR-based security group rule already exists, skipping creation"
    else
        # Add the VPC CIDR-based security group rule (no cross-reference dependency)
        aws ec2 authorize-security-group-ingress \
            --group-id "$source_sg_id" \
            --protocol tcp \
            --port 443 \
            --cidr "$vpc_cidr" \
            --region "$REGION" 2>/dev/null || echo "Warning: Failed to add security group rule (may already exist)"
        
        echo "VPC CIDR-based security group rule added successfully"
        echo "Rule allows HTTPS access from entire VPC ($vpc_cidr) instead of specific security group"
    fi
    
    # Store the security group IDs in SSM for future reference
    aws ssm put-parameter \
        --name "/migration/${STAGE}/default/configuredSourceSecurityGroupId" \
        --value "$source_sg_id" \
        --type "String" \
        --overwrite \
        --region "$REGION" || echo "Warning: Failed to store configured source security group ID in SSM"
    
    echo "Security group configuration completed"
    echo "Migration console can now access source cluster via VPC CIDR-based rule (no cross-reference dependencies)"
}

# Verify migration console connectivity
verify_migration_console() {
    echo "Verifying migration console connectivity..."
    
    # Get ECS cluster name
    local ecs_cluster="migration-${STAGE}-ecs-cluster"
    
    # Check if migration console task is running
    local task_arn=$(aws ecs list-tasks \
        --cluster "$ecs_cluster" \
        --family "migration-${STAGE}-migration-console" \
        --region "$REGION" \
        --query 'taskArns[0]' \
        --output text 2>/dev/null)
    
    if [ -z "$task_arn" ] || [ "$task_arn" = "None" ]; then
        echo "Warning: Migration console task not found or not running"
        echo "ECS Cluster: $ecs_cluster"
        return 1
    fi
    
    echo "Migration console task found: $task_arn"
    echo "Migration console is ready for testing"
    
    # Output connection information
    echo "=== MIGRATION INFRASTRUCTURE INFORMATION ==="
    echo "ECS_CLUSTER=$ecs_cluster"
    echo "MIGRATION_CONSOLE_TASK=$task_arn"
    echo "SOURCE_ENDPOINT=$SOURCE_ENDPOINT"
    echo "TARGET_ENDPOINT=$SOURCE_ENDPOINT"
    echo "REGION=$REGION"
    echo "STAGE=$STAGE"
    echo "============================================="
}

# Cleanup temporary files
cleanup_temp_files() {
    if [ -d "$TMP_DIR_PATH" ]; then
        echo "Cleaning up temporary files: $TMP_DIR_PATH"
        rm -rf "$TMP_DIR_PATH"
    fi
}


# Cleanup deployed migration infrastructure
cleanup_migration_infrastructure() {
    echo "Cleaning up migration infrastructure..."
    
    cd "$MIGRATION_CDK_PATH"
    
    # Recreate the migration context file for cleanup
    generate_migration_context
    
    # Destroy all migration stacks with proper context
    # No security group dependency cleanup needed - using VPC CIDR instead of cross-references
    cdk destroy "*" \
        --c contextFile="$TMP_DIR_PATH/migrationContext.json" \
        --c contextId="default" \
        --force
    if [ $? -ne 0 ]; then
        echo "Warning: Some stacks may not have been destroyed completely"
    fi
    
    echo "Migration infrastructure cleanup completed"
}

# Usage information
usage() {
    echo ""
    echo "AWS Migration Infrastructure Setup Script"
    echo "Purpose: Deploy Migration Assistant infrastructure with dynamic context generation"
    echo ""
    echo "Usage:"
    echo "  ./awsMigrationInfraSetup.sh --source-endpoint https://... --source-version ES_7.10 --vpc-id vpc-123 --stage dev"
    echo ""
    echo "Options:"
    echo "  --source-endpoint    Required. Source cluster endpoint URL"
    echo "  --source-version     Required. Source cluster version (ES_5.6, ES_6.8, ES_7.10)"
    echo "  --vpc-id            Required. VPC ID where source cluster is deployed"
    echo "  --stage             Stage name for deployment (default: dev)"
    echo "  --region            AWS region (default: us-west-2)"
    echo "  --cleanup           Cleanup deployed migration infrastructure and exit"
    echo "  -h, --help          Show this help message"
    echo ""
    echo "Examples:"
    echo "  ./awsMigrationInfraSetup.sh --source-endpoint https://search-test.aos.us-west-2.on.aws --source-version ES_7.10 --vpc-id vpc-123456 --stage test"
    echo "  ./awsMigrationInfraSetup.sh --cleanup --stage test"
    echo ""
    exit 1
}

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --source-endpoint)
            SOURCE_ENDPOINT="$2"
            shift 2
            ;;
        --source-version)
            SOURCE_VERSION="$2"
            shift 2
            ;;
        --vpc-id)
            VPC_ID="$2"
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

# Set AWS region
export AWS_DEFAULT_REGION="$REGION"

# Handle cleanup mode
if [ "$CLEANUP" = true ]; then
    # Validate required parameters for cleanup
    if [ -z "$SOURCE_ENDPOINT" ] || [ -z "$SOURCE_VERSION" ] || [ -z "$VPC_ID" ]; then
        echo "Error: Cleanup requires the same parameters used during deployment:"
        echo "  --source-endpoint (original endpoint used)"
        echo "  --source-version (original version used)"
        echo "  --vpc-id (original VPC ID used)"
        echo ""
        echo "Example:"
        echo "  ./awsMigrationInfraSetup.sh --cleanup --source-endpoint https://... --source-version ES_7.10 --vpc-id vpc-123 --stage dev --region us-west-2"
        exit 1
    fi
    cleanup_migration_infrastructure
    exit 0
fi

# Validate required parameters
if [ -z "$SOURCE_ENDPOINT" ]; then
    echo "Error: --source-endpoint is required"
    usage
fi

if [ -z "$SOURCE_VERSION" ]; then
    echo "Error: --source-version is required"
    usage
fi

if [ -z "$VPC_ID" ]; then
    echo "Error: --vpc-id is required"
    usage
fi

# Map version to family
map_version_to_family

echo "Starting AWS Migration Infrastructure Setup..."
echo "Source Endpoint: $SOURCE_ENDPOINT"
echo "Source Version: $SOURCE_VERSION"
echo "Version Family: $VERSION_FAMILY"
echo "VPC ID: $VPC_ID"
echo "Stage: $STAGE"
echo "Region: $REGION"

# Execute deployment steps
create_service_linked_roles
generate_migration_context
build_docker_images
install_cdk_dependencies
check_and_bootstrap_region "$REGION"
deploy_migration_infrastructure
configure_source_cluster_security_groups
verify_migration_console
cleanup_temp_files

echo "AWS Migration Infrastructure Setup completed successfully!"
