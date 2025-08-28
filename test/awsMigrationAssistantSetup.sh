#!/bin/bash

# AWS Migration Assistant Setup Script
# Purpose: Deploy Migration Assistant infrastructure and connect it to target cluster
# This script focuses solely on migration infrastructure deployment and configuration

set -e

# Script paths and directories
SCRIPT_ABS_PATH=$(readlink -f "$0")
TEST_DIR_PATH=$(dirname "$SCRIPT_ABS_PATH")
ROOT_REPO_PATH=$(dirname "$TEST_DIR_PATH")
MIGRATION_CDK_PATH="$ROOT_REPO_PATH/deployment/cdk/opensearch-service-migration"
TMP_DIR_PATH="$TEST_DIR_PATH/tmp"

# Default values
TARGET_ENDPOINT=""
TARGET_VERSION=""
VPC_ID=""
SNAPSHOT_S3_URI=""
SNAPSHOT_NAME=""
SNAPSHOT_REPO_NAME=""
STAGE="rfs-metrics"
REGION="us-west-2"
CLEANUP=false

# Create required service-linked roles
create_service_linked_roles() {
    echo "Creating required service-linked roles..."
    aws iam create-service-linked-role --aws-service-name opensearchservice.amazonaws.com 2>/dev/null || echo "OpenSearch service-linked role already exists"
    aws iam create-service-linked-role --aws-service-name ecs.amazonaws.com 2>/dev/null || echo "ECS service-linked role already exists"
    aws iam create-service-linked-role --aws-service-name osis.amazonaws.com 2>/dev/null || echo "OSIS service-linked role already exists"
    echo "Service-linked roles creation completed"
}

# Generate migration context with dynamic values for external snapshot migration
generate_migration_context() {
    echo "Generating migration context for external snapshot migration..."
    
    local context_file="$TMP_DIR_PATH/migrationContext.json"
    
    mkdir -p "$TMP_DIR_PATH"
    
    # Parse S3 URI components
    local s3_bucket=$(echo "$SNAPSHOT_S3_URI" | sed 's|s3://||' | cut -d'/' -f1)
    local s3_path=$(echo "$SNAPSHOT_S3_URI" | sed 's|s3://[^/]*/||')
    
    echo "Generating migration context with:"
    echo "  Stage: $STAGE"
    echo "  Target Endpoint: $TARGET_ENDPOINT"
    echo "  VPC ID: $VPC_ID"
    echo "  S3 Bucket: $s3_bucket"
    echo "  S3 Path: $s3_path"
    echo "  Snapshot Name: $SNAPSHOT_NAME"
    echo "  Snapshot Repo: $SNAPSHOT_REPO_NAME"
    echo "  Region: $REGION"
    
    cat > "$context_file" << EOF
{
  "default": {
    "stage": "${STAGE}",
    "targetCluster": {
      "endpoint": "${TARGET_ENDPOINT}",
      "allow_insecure": true,
      "auth": {
        "type": "sigv4",
        "region": "${REGION}",
        "serviceSigningName": "es"
      }
    },
    "sourceCluster": {
      "disabled": true,
      "version": "ES 7.10"
    },
    "snapshot": {
      "snapshotName": "${SNAPSHOT_NAME}",
      "snapshotRepoName": "${SNAPSHOT_REPO_NAME}",
      "s3Uri": "${SNAPSHOT_S3_URI}",
      "s3Region": "${REGION}"
    },
    "MskEbsStorage": {
      "maxCapacity": 16384
    },
    "vpcId": "${VPC_ID}",
    "vpcAZCount": 2,
    "reindexFromSnapshotServiceEnabled": true,
    "reindexFromSnapshotMaxShardSizeGiB": 80,
    "artifactBucketRemovalPolicy": "DESTROY",
    "managedServiceSourceSnapshotEnabled": false,
    "otelCollectorEnabled": true,
    "migrationConsoleServiceEnabled": true,
    "trafficReplayerServiceEnabled": false,
    "captureProxyServiceEnabled": false,
    "targetClusterProxyServiceEnabled": false
  }
}
EOF
    
    echo "Migration context created at: $context_file"
    echo "Context configured for external snapshot migration with:"
    echo "  - Target cluster: ${TARGET_ENDPOINT}"
    echo "  - Source cluster: disabled (external snapshot mode)"
    echo "  - Snapshot S3 URI: ${SNAPSHOT_S3_URI}"
    echo "  - Migration services: console + RFS enabled, traffic replay disabled"
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
        cd "$MIGRATION_CDK_PATH"
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
    cd "$TEST_DIR_PATH"
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
    cd "$TEST_DIR_PATH"
}

# Deploy migration infrastructure
deploy_migration_infrastructure() {
    echo "Deploying migration infrastructure..."
    
    cd "$MIGRATION_CDK_PATH"
    
    # Verify migration context file exists
    if [ ! -f "$TMP_DIR_PATH/migrationContext.json" ]; then
        echo "Error: Migration context file not found at $TMP_DIR_PATH/migrationContext.json"
        echo "Expected file to be created by generate_migration_context function"
        exit 1
    fi
    
    # Deploy CDK stacks with generated context
    echo "Deploying CDK stacks with migration context..."
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
    cd "$TEST_DIR_PATH"
}

# Configure security groups for target cluster connectivity
configure_target_cluster_security_groups() {
    echo "Configuring security groups for target cluster connectivity..."
    
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
    
    # Discover target cluster security group dynamically in the VPC
    echo "Discovering target cluster security group in VPC: ${VPC_ID}..."
    
    # Try to find target cluster security groups in the VPC
    local target_sg_id=$(aws ec2 describe-security-groups \
        --filters "Name=vpc-id,Values=${VPC_ID}" "Name=group-name,Values=*cluster-access*" \
        --query 'SecurityGroups[0].GroupId' \
        --output text \
        --region "$REGION" 2>/dev/null)
    
    if [ -z "$target_sg_id" ] || [ "$target_sg_id" = "None" ]; then
        # Try alternative naming patterns for target cluster
        target_sg_id=$(aws ec2 describe-security-groups \
            --filters "Name=vpc-id,Values=${VPC_ID}" "Name=group-name,Values=*opensearch*" \
            --query 'SecurityGroups[0].GroupId' \
            --output text \
            --region "$REGION" 2>/dev/null)
    fi
    
    if [ -z "$target_sg_id" ] || [ "$target_sg_id" = "None" ]; then
        # Try generic cluster pattern
        target_sg_id=$(aws ec2 describe-security-groups \
            --filters "Name=vpc-id,Values=${VPC_ID}" "Name=group-name,Values=*cluster*" \
            --query 'SecurityGroups[0].GroupId' \
            --output text \
            --region "$REGION" 2>/dev/null)
    fi
    
    if [ -z "$target_sg_id" ] || [ "$target_sg_id" = "None" ]; then
        echo "Warning: Could not find target cluster security group ID"
        echo "Manual security group configuration may be required"
        return 1
    fi
    
    echo "Found target cluster security group: $target_sg_id"
    
    # Add inbound rule to target cluster security group using VPC CIDR (no cross-reference)
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
        --group-ids "$target_sg_id" \
        --query "SecurityGroups[0].IpPermissions[?FromPort==\`443\` && ToPort==\`443\` && IpRanges[?CidrIp==\`${vpc_cidr}\`]]" \
        --output text \
        --region "$REGION" 2>/dev/null)
    
    if [ -n "$existing_rule" ] && [ "$existing_rule" != "None" ]; then
        echo "VPC CIDR-based security group rule already exists, skipping creation"
    else
        # Add the VPC CIDR-based security group rule (no cross-reference dependency)
        aws ec2 authorize-security-group-ingress \
            --group-id "$target_sg_id" \
            --protocol tcp \
            --port 443 \
            --cidr "$vpc_cidr" \
            --region "$REGION" 2>/dev/null || echo "Warning: Failed to add security group rule (may already exist)"
        
        echo "VPC CIDR-based security group rule added successfully"
        echo "Rule allows HTTPS access from entire VPC ($vpc_cidr) instead of specific security group"
    fi
    
    # Store the security group IDs in SSM for future reference
    aws ssm put-parameter \
        --name "/migration/${STAGE}/default/configuredTargetSecurityGroupId" \
        --value "$target_sg_id" \
        --type "String" \
        --overwrite \
        --region "$REGION" || echo "Warning: Failed to store configured target security group ID in SSM"
    
    echo "Security group configuration completed"
    echo "Migration console can now access target cluster via VPC CIDR-based rule (no cross-reference dependencies)"
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
    
    # Output structured information for pipeline parsing
    echo "=== MIGRATION INFRASTRUCTURE INFORMATION ==="
    echo "ECS_CLUSTER=$ecs_cluster"
    echo "MIGRATION_CONSOLE_TASK=$task_arn"
    echo "TARGET_ENDPOINT=$TARGET_ENDPOINT"
    echo "SNAPSHOT_S3_URI=$SNAPSHOT_S3_URI"
    echo "VPC_ID=$VPC_ID"
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
    echo "Recreating migration context for cleanup..."
    generate_migration_context
    
    # Destroy all migration stacks with proper context
    # No security group dependency cleanup needed - using VPC CIDR instead of cross-references
    echo "Destroying migration infrastructure stacks..."
    cdk destroy "*" \
        --c contextFile="$TMP_DIR_PATH/migrationContext.json" \
        --c contextId="default" \
        --force
    if [ $? -ne 0 ]; then
        echo "Warning: Some stacks may not have been destroyed completely"
    fi
    
    echo "Migration infrastructure cleanup completed"
    cd "$TEST_DIR_PATH"
}

# Usage information
usage() {
    echo ""
    echo "AWS Migration Assistant Setup Script"
    echo "Purpose: Deploy Migration Assistant infrastructure and connect to target cluster"
    echo ""
    echo "Usage:"
    echo "  ./awsMigrationAssistantSetup.sh --target-endpoint https://... --vpc-id vpc-... --snapshot-s3-uri s3://..."
    echo ""
    echo "Options:"
    echo "  --target-endpoint    Required. Target cluster HTTPS endpoint"
    echo "  --target-version     Target cluster version (default: OS_2.19)"
    echo "  --vpc-id            Required. VPC ID where target cluster is deployed"
    echo "  --snapshot-s3-uri   Required. S3 URI for snapshot source data"
    echo "  --snapshot-name     Required. Snapshot name"
    echo "  --snapshot-repo     Required. Snapshot repository name"
    echo "  --stage             Stage name for deployment (default: rfs-metrics)"
    echo "  --region            AWS region (default: us-west-2)"
    echo "  --cleanup           Cleanup deployed resources and exit"
    echo "  -h, --help          Show this help message"
    echo ""
    echo "Examples:"
    echo "  ./awsMigrationAssistantSetup.sh \\"
    echo "    --target-endpoint https://vpc-target-os2x-rfs-metrics-xyz.us-west-2.es.amazonaws.com \\"
    echo "    --vpc-id vpc-0215567a9ce78cae9 \\"
    echo "    --snapshot-s3-uri s3://bucket/snapshot-repo"
    echo ""
    echo "  ./awsMigrationAssistantSetup.sh --cleanup --stage rfs-metrics"
    echo ""
    exit 1
}

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --target-endpoint)
            TARGET_ENDPOINT="$2"
            shift 2
            ;;
        --target-version)
            TARGET_VERSION="$2"
            shift 2
            ;;
        --vpc-id)
            VPC_ID="$2"
            shift 2
            ;;
        --snapshot-s3-uri)
            SNAPSHOT_S3_URI="$2"
            shift 2
            ;;
        --snapshot-name)
            SNAPSHOT_NAME="$2"
            shift 2
            ;;
        --snapshot-repo)
            SNAPSHOT_REPO_NAME="$2"
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

# Main execution
if [ "$CLEANUP" = true ]; then
    cleanup_migration_infrastructure
    cleanup_temp_files
    exit 0
fi

# Validate required parameters for deployment
if [ -z "$TARGET_ENDPOINT" ]; then
    echo "Error: --target-endpoint is required"
    usage
fi

if [ -z "$VPC_ID" ]; then
    echo "Error: --vpc-id is required"
    usage
fi

if [ -z "$SNAPSHOT_S3_URI" ]; then
    echo "Error: --snapshot-s3-uri is required"
    usage
fi

if [ -z "$SNAPSHOT_NAME" ]; then
    echo "Error: --snapshot-name is required"
    usage
fi

if [ -z "$SNAPSHOT_REPO_NAME" ]; then
    echo "Error: --snapshot-repo is required"
    usage
fi

# Set default target version if not provided
if [ -z "$TARGET_VERSION" ]; then
    TARGET_VERSION="OS_2.19"
fi

echo "Starting AWS Migration Assistant Setup..."
echo "Target Endpoint: $TARGET_ENDPOINT"
echo "Target Version: $TARGET_VERSION"
echo "VPC ID: $VPC_ID"
echo "Snapshot S3 URI: $SNAPSHOT_S3_URI"
echo "Stage: $STAGE"
echo "Region: $REGION"

# Execute deployment pipeline
create_service_linked_roles
generate_migration_context
build_docker_images
install_cdk_dependencies
check_and_bootstrap_region "$REGION"
deploy_migration_infrastructure
configure_target_cluster_security_groups
verify_migration_console
cleanup_temp_files

echo "AWS Migration Assistant Setup completed successfully!"
