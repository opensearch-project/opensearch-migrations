#!/bin/bash

# AWS Migration Infrastructure Setup Script
# Purpose: Deploy Migration Assistant infrastructure with dynamic context generation
# Usage: ./awsMigrationInfraSetup.sh --source-endpoint https://... --source-version ES_7.10 --vpc-id vpc-123 --stage dev

set -e

script_abs_path=$(readlink -f "$0")
TEST_DIR_PATH=$(dirname "$script_abs_path")
ROOT_REPO_PATH=$(dirname "$TEST_DIR_PATH")
MIGRATION_CDK_PATH="$ROOT_REPO_PATH/deployment/cdk/opensearch-service-migration"
TMP_DIR_PATH="$TEST_DIR_PATH/tmp"

# Default values
SOURCE_ENDPOINT=""
SOURCE_VERSION=""
VPC_ID=""
STAGE="dev"
REGION="us-west-2"
CLEANUP=false

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
        *)
            echo "Error: Unsupported source version: $SOURCE_VERSION"
            echo "Supported versions: ES_5.6, ES_6.8, ES_7.10"
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
    
    # Destroy all migration stacks
    cdk destroy "*" --force
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
generate_migration_context
build_docker_images
install_cdk_dependencies
check_and_bootstrap_region "$REGION"
deploy_migration_infrastructure
verify_migration_console
cleanup_temp_files

echo "AWS Migration Infrastructure Setup completed successfully!"
