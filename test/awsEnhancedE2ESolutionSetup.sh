#!/bin/bash

# Enhanced E2E Solution Setup Script for Snapshot-Based Migration Pipeline
# This script extends the existing awsE2ESolutionSetup.sh to support:
# 1. Target OpenSearch 2.19 cluster deployment (using AWS Solutions CDK)
# 2. Migration Assistant deployment for snapshot-based migration
# 3. Dual cleanup process for both target cluster and migration infrastructure

script_abs_path=$(readlink -f "$0")
TEST_DIR_PATH=$(dirname "$script_abs_path")
ROOT_REPO_PATH=$(dirname "$TEST_DIR_PATH")
TMP_DIR_PATH="$TEST_DIR_PATH/tmp"
EC2_SOURCE_CDK_PATH="$ROOT_REPO_PATH/test/opensearch-cluster-cdk"
MIGRATION_CDK_PATH="$ROOT_REPO_PATH/deployment/cdk/opensearch-service-migration"

# Enhanced functions for target cluster deployment
deploy_target_cluster() {
    local target_context_file=$1
    local target_context_id=$2
    local stage=$3
    
    echo "Deploying target OpenSearch 2.19 cluster..."
    
    # Clone or update AWS Solutions CDK if needed
    if [ ! -d "opensearch-cluster-cdk" ]; then
        git clone https://github.com/lewijacn/opensearch-cluster-cdk.git
    else
        echo "Repo already exists, updating..."
        cd opensearch-cluster-cdk && git pull && cd ..
    fi
    
    cd opensearch-cluster-cdk && git checkout migration-es && git pull
    npm install
    
    # Deploy target cluster
    echo "Deploying target cluster with context: $target_context_file, ID: $target_context_id"
    cdk deploy "*" --c contextFile="$target_context_file" --c contextId="$target_context_id" --require-approval never
    
    if [ $? -ne 0 ]; then
        echo "Error: deploy target cluster failed, exiting."
        exit 1
    fi
    
    cd "$TEST_DIR_PATH"
    echo "Target cluster deployment completed successfully"
}

deploy_migration_assistant() {
    local migration_context_file=$1
    local migration_context_id=$2
    local stage=$3
    
    echo "Deploying Migration Assistant infrastructure..."
    
    cd "$MIGRATION_CDK_PATH" || exit
    ./buildDockerImages.sh
    if [ $? -ne 0 ]; then
        echo "Error: building docker images failed, exiting."
        exit 1
    fi
    
    npm install
    cdk deploy "*" --c contextFile="$migration_context_file" --c contextId="$migration_context_id" --require-approval never --concurrency 3
    if [ $? -ne 0 ]; then
        echo "Error: deploying migration stacks failed, exiting."
        exit 1
    fi
    
    cd "$TEST_DIR_PATH"
    echo "Migration Assistant deployment completed successfully"
}

cleanup_target_cluster() {
    local stage=$1
    
    echo "Cleaning up target cluster resources..."
    
    # Get target cluster stack names
    local target_network_stack="opensearch-network-stack-target-os219-$stage"
    local target_infra_stack="opensearch-infra-stack-target-os219-$stage"
    
    cd "$EC2_SOURCE_CDK_PATH" || exit
    
    # Use a temporary context file for cleanup
    local cleanup_context="{\"target-os219-context\":{\"suffix\":\"target-os219-$stage\",\"networkStackSuffix\":\"target-os219-$stage\"}}"
    echo "$cleanup_context" > "$TMP_DIR_PATH/cleanupTargetContext.json"
    
    cdk destroy "*" --force --c contextFile="$TMP_DIR_PATH/cleanupTargetContext.json" --c contextId="target-os219-context"
    
    cd "$TEST_DIR_PATH"
    echo "Target cluster cleanup completed"
}

cleanup_migration_assistant() {
    local stage=$1
    
    echo "Cleaning up Migration Assistant resources..."
    
    cd "$MIGRATION_CDK_PATH" || exit
    
    # Use a temporary context file for cleanup
    local cleanup_context="{\"snapshot-migration-context\":{\"stage\":\"$stage\"}}"
    echo "$cleanup_context" > "$TMP_DIR_PATH/cleanupMigrationContext.json"
    
    cdk destroy "*" --force --c contextFile="$TMP_DIR_PATH/cleanupMigrationContext.json" --c contextId="snapshot-migration-context"
    
    cd "$TEST_DIR_PATH"
    echo "Migration Assistant cleanup completed"
}

# One-time required service-linked-role creation for AWS accounts which do not have these roles
create_service_linked_roles() {
    aws iam create-service-linked-role --aws-service-name opensearchservice.amazonaws.com || true
    aws iam create-service-linked-role --aws-service-name ecs.amazonaws.com || true
    aws iam create-service-linked-role --aws-service-name osis.amazonaws.com || true
}

# One-time required CDK bootstrap setup for a given region
bootstrap_region() {
    # Use a minimal context for bootstrap
    local bootstrap_context="{\"bootstrap-context\":{\"suffix\":\"bootstrap\",\"networkStackSuffix\":\"bootstrap\"}}"
    echo "$bootstrap_context" > "$TMP_DIR_PATH/bootstrapContext.json"
    
    cd "$EC2_SOURCE_CDK_PATH" || exit
    cdk bootstrap --require-approval never --c contextFile="$TMP_DIR_PATH/bootstrapContext.json" --c contextId="bootstrap-context"
    cd "$TEST_DIR_PATH"
}

usage() {
    echo ""
    echo "Enhanced E2E Solution Setup Script for Snapshot-Based Migration Pipeline"
    echo "Supports target cluster deployment, migration assistant deployment, and cleanup operations."
    echo ""
    echo "Usage: "
    echo "  ./awsEnhancedE2ESolutionSetup.sh [options]"
    echo ""
    echo "Deployment Options:"
    echo "  --target-context-file                        A file path for target cluster context, default is './targetClusterContext.json'."
    echo "  --target-context-id                          The CDK context block identifier for target cluster, default is 'target-os219-context'."
    echo "  --migration-context-file                     A file path for migration context, default is './snapshotMigrationContext.json'."
    echo "  --migration-context-id                       The CDK context block identifier for migration, default is 'snapshot-migration-context'."
    echo "  --stage                                      The stage name to use for naming/grouping of AWS deployment resources, default is 'snapshot-perf'."
    echo "  --migrations-git-url                         The Github http url, default is 'https://github.com/opensearch-project/opensearch-migrations.git'."
    echo "  --migrations-git-branch                      The Github branch, default is 'main'."
    echo ""
    echo "Deployment Mode Options (choose one):"
    echo "  --deploy-target-only                         Deploy only the target OpenSearch 2.19 cluster"
    echo "  --deploy-migration-only                      Deploy only the Migration Assistant infrastructure"
    echo "  --deploy-both                                Deploy both target cluster and migration assistant (default)"
    echo ""
    echo "Cleanup Options:"
    echo "  --cleanup-target                             Cleanup only target cluster resources"
    echo "  --cleanup-migration                          Cleanup only migration assistant resources"
    echo "  --cleanup-all                                Cleanup all resources (both target and migration)"
    echo ""
    echo "Setup Options:"
    echo "  --create-service-linked-roles                Flag to create required service linked roles for the AWS account"
    echo "  --bootstrap-region                           Flag to CDK bootstrap the region to allow CDK deployments"
    echo "  --skip-capture-proxy                         Flag to skip setting up the Capture Proxy (always true for snapshot-based migration)"
    echo ""
    exit 1
}

# Default values
STAGE='snapshot-perf'
CREATE_SLR=false
BOOTSTRAP_REGION=false
SKIP_CAPTURE_PROXY=true  # Always true for snapshot-based migration
TARGET_CONTEXT_FILE='./targetClusterContext.json'
MIGRATION_CONTEXT_FILE='./snapshotMigrationContext.json'
TARGET_CONTEXT_ID='target-os219-context'
MIGRATION_CONTEXT_ID='snapshot-migration-context'
MIGRATIONS_GIT_URL='https://github.com/opensearch-project/opensearch-migrations.git'
MIGRATIONS_GIT_BRANCH='main'

# Deployment mode flags
DEPLOY_TARGET_ONLY=false
DEPLOY_MIGRATION_ONLY=false
DEPLOY_BOTH=false

# Cleanup flags
CLEANUP_TARGET=false
CLEANUP_MIGRATION=false
CLEANUP_ALL=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --create-service-linked-roles)
            CREATE_SLR=true
            shift
            ;;
        --bootstrap-region)
            BOOTSTRAP_REGION=true
            shift
            ;;
        --skip-capture-proxy)
            SKIP_CAPTURE_PROXY=true
            shift
            ;;
        --target-context-file)
            TARGET_CONTEXT_FILE="$2"
            shift 2
            ;;
        --target-context-id)
            TARGET_CONTEXT_ID="$2"
            shift 2
            ;;
        --migration-context-file)
            MIGRATION_CONTEXT_FILE="$2"
            shift 2
            ;;
        --migration-context-id)
            MIGRATION_CONTEXT_ID="$2"
            shift 2
            ;;
        --migrations-git-url)
            MIGRATIONS_GIT_URL="$2"
            shift 2
            ;;
        --migrations-git-branch)
            MIGRATIONS_GIT_BRANCH="$2"
            shift 2
            ;;
        --stage)
            STAGE="$2"
            shift 2
            ;;
        --deploy-target-only)
            DEPLOY_TARGET_ONLY=true
            shift
            ;;
        --deploy-migration-only)
            DEPLOY_MIGRATION_ONLY=true
            shift
            ;;
        --deploy-both)
            DEPLOY_BOTH=true
            shift
            ;;
        --cleanup-target)
            CLEANUP_TARGET=true
            shift
            ;;
        --cleanup-migration)
            CLEANUP_MIGRATION=true
            shift
            ;;
        --cleanup-all)
            CLEANUP_ALL=true
            shift
            ;;
        -h|--h|--help)
            usage
            ;;
        -*)
            echo "Unknown option $1"
            usage
            ;;
        *)
            shift
            ;;
    esac
done

# Set default deployment mode if none specified
if [ "$DEPLOY_TARGET_ONLY" = false ] && [ "$DEPLOY_MIGRATION_ONLY" = false ] && [ "$DEPLOY_BOTH" = false ] && [ "$CLEANUP_TARGET" = false ] && [ "$CLEANUP_MIGRATION" = false ] && [ "$CLEANUP_ALL" = false ]; then
    DEPLOY_BOTH=true
fi

# Create tmp directory
mkdir -p "$TMP_DIR_PATH"

# Handle service linked roles creation
if [ "$CREATE_SLR" = true ]; then
    create_service_linked_roles
fi

# Handle CDK bootstrap
if [ "$BOOTSTRAP_REGION" = true ]; then
    bootstrap_region
fi

# Handle cleanup operations
if [ "$CLEANUP_ALL" = true ]; then
    cleanup_migration_assistant "$STAGE"
    cleanup_target_cluster "$STAGE"
    exit 0
fi

if [ "$CLEANUP_TARGET" = true ]; then
    cleanup_target_cluster "$STAGE"
    exit 0
fi

if [ "$CLEANUP_MIGRATION" = true ]; then
    cleanup_migration_assistant "$STAGE"
    exit 0
fi

# Handle deployment operations
if [ "$DEPLOY_TARGET_ONLY" = true ] || [ "$DEPLOY_BOTH" = true ]; then
    echo "Starting target cluster deployment..."
    
    # Prepare target context file with stage substitution
    TARGET_GEN_CONTEXT_FILE="$TMP_DIR_PATH/generatedTargetContext.json"
    cp "$TARGET_CONTEXT_FILE" "$TARGET_GEN_CONTEXT_FILE"
    sed -i -e "s/<STAGE>/$STAGE/g" "$TARGET_GEN_CONTEXT_FILE"
    
    deploy_target_cluster "$TARGET_GEN_CONTEXT_FILE" "$TARGET_CONTEXT_ID" "$STAGE"
    
    # Extract target cluster information for migration context
    TARGET_NETWORK_STACK_NAME="opensearch-network-stack-target-os219-$STAGE"
    TARGET_INFRA_STACK_NAME="opensearch-infra-stack-target-os219-$STAGE"
    
    target_vpc_id=$(aws cloudformation describe-stacks --stack-name "$TARGET_NETWORK_STACK_NAME" --query "Stacks[0].Outputs[?contains(OutputValue, 'vpc')].OutputValue" --output text 2>/dev/null || echo "")
    target_endpoint=$(aws cloudformation describe-stacks --stack-name "$TARGET_INFRA_STACK_NAME" --query "Stacks[0].Outputs[?OutputKey==\`domainEndpoint\`].OutputValue" --output text 2>/dev/null || echo "")
    
    echo "Target VPC ID: $target_vpc_id"
    echo "Target Endpoint: $target_endpoint"
    
    # Export for use by migration deployment
    export TARGET_VPC_ID="$target_vpc_id"
    export TARGET_ENDPOINT="$target_endpoint"
fi

if [ "$DEPLOY_MIGRATION_ONLY" = true ] || [ "$DEPLOY_BOTH" = true ]; then
    echo "Starting migration assistant deployment..."
    
    # Prepare migration context file with substitutions
    MIGRATION_GEN_CONTEXT_FILE="$TMP_DIR_PATH/generatedMigrationContext.json"
    cp "$MIGRATION_CONTEXT_FILE" "$MIGRATION_GEN_CONTEXT_FILE"
    sed -i -e "s/<STAGE>/$STAGE/g" "$MIGRATION_GEN_CONTEXT_FILE"
    
    # If we have target cluster info from previous deployment, use it
    if [ -n "$TARGET_VPC_ID" ] && [ -n "$TARGET_ENDPOINT" ]; then
        sed -i -e "s/<VPC_ID>/$TARGET_VPC_ID/g" "$MIGRATION_GEN_CONTEXT_FILE"
        sed -i -e "s|<TARGET_CLUSTER_ENDPOINT>|https://$TARGET_ENDPOINT:443|g" "$MIGRATION_GEN_CONTEXT_FILE"
    fi
    
    deploy_migration_assistant "$MIGRATION_GEN_CONTEXT_FILE" "$MIGRATION_CONTEXT_ID" "$STAGE"
fi

echo "Enhanced E2E solution setup completed successfully!"
