#!/bin/bash

################################################################################
# AWS Permissions Discovery Script
################################################################################
#
# PURPOSE:
#   This script extracts and displays the AWS IAM permissions and resource 
#   types required by the OpenSearch Migration Assistant deployment. It helps
#   you understand what AWS permissions are needed BEFORE attempting to deploy.
#
# USE CASES:
#   - Creating least-privilege IAM policies
#   - Understanding the full scope of AWS resources that will be created
#
################################################################################
# PREREQUISITES
################################################################################
#
# Before running this script, ensure you have:
#
# 1. Node.js 18 or higher installed
# 2. AWS CDK CLI installed globally:
#    $ npm install -g aws-cdk@latest
#
# 3. Java 11 (required by the opensearch-migrations project)
# 4. Docker (for building container images)
#
################################################################################
# SETUP STEPS
################################################################################
#
# 1. Navigate to this directory:
#    $ cd deployment/cdk/opensearch-service-migration
#
# 2. Install Node.js dependencies:
#    $ npm install
#
# 3. Build Docker images (one-time setup):
#    $ cd ../../..  # Return to repo root
#    $ ./deployment/cdk/opensearch-service-migration/buildDockerImages.sh
#    $ cd deployment/cdk/opensearch-service-migration
#
# 4. (Optional) Configure AWS credentials:
#    $ aws configure
#
################################################################################
# USAGE
################################################################################
#
# Basic usage:
#   $ ./awsFeatureUsage.sh <contextId>
#
# Using the demo context:
#   $ ./awsFeatureUsage.sh demo-deploy
#
# Using a custom context:
#   $ ./awsFeatureUsage.sh my-custom-context
#
# The contextId should match a configuration block in your cdk.context.json file.
#
################################################################################
# OUTPUT
################################################################################
#
# The script generates two main sections:
#
# 1. IAM Policy Actions - A sorted list of AWS IAM permissions required
#    Example output:
#      acm:DescribeCertificate
#      ec2:CreateVpc
#      ecs:CreateCluster
#      s3:CreateBucket
#      ...
#
# 2. Resource Types - All AWS CloudFormation resource types that will be created
#    Example output:
#      AWS::EC2::VPC
#      AWS::ECS::Cluster
#      AWS::OpenSearchService::Domain
#      AWS::S3::Bucket
#      ...
#
################################################################################
# USING THE OUTPUT
################################################################################
#
# Create a Least-Privilege IAM Policy:
#   Use the "IAM Policy Actions" list to create a custom IAM policy for your
#   deployment user/role. Example:
#
#   {
#     "Version": "2012-10-17",
#     "Statement": [{
#       "Effect": "Allow",
#       "Action": [
#         "acm:DescribeCertificate",
#         "ec2:CreateVpc",
#         // ... add all actions from script output
#       ],
#       "Resource": "*"
#     }]
#   }
#
################################################################################
# TROUBLESHOOTING
################################################################################
#
# Error: "contextId is required"
#   - Ensure you're passing a context ID as the first argument
#   - Check that the context exists in your cdk.context.json file
#
# Error: TypeScript compilation errors
#   - Run: npm install
#   - Ensure you have Node.js 18 or higher: node --version
#
# Error: "Cannot find module 'aws-cdk-lib'"
#   - Run: npm install
#
# No output or empty lists
#   - Verify the context ID exists in cdk.context.json
#   - Check that services are enabled in your context configuration
#
################################################################################
# ADDITIONAL RESOURCES
################################################################################
#
# - Deployment options: ./options.md
# - CDK deployment guide: ./README.md  
# - AWS CDK documentation: https://docs.aws.amazon.com/cdk/
#
################################################################################

set -e

if [ -z "$1" ]; then
    echo "Error: contextId is required. Please pass it as the first argument to the script."
    echo "Usage: $0 <contextId>"
    exit 1
fi

contextId="$1"

output_dir="cdk-synth-output"
rm -rf "$output_dir"
mkdir -p "$output_dir"

echo "Synthesizing all stacks..."
raw_stacks=$(cdk list --ci --context contextId=$contextId)

echo "$raw_stacks" | sed -E 's/ *\(.*\)//' | while read -r stack; do
    echo "Synthesizing stack: $stack"
    cdk synth $stack --ci --context contextId=$contextId > "$output_dir/$stack.yaml"
done

echo "Finding resource usage from synthesized stacks..."
echo "-----------------------------------"
echo "IAM Policy Actions:"

grep -h -A 1000 "PolicyDocument:" "$output_dir"/*.yaml | \
grep -E "Action:" -A 50 | \
grep -E "^[ \t-]+[a-zA-Z0-9]+:[a-zA-Z0-9\*]+" | \
grep -vE "^[ \t-]+(aws:|arn:)" | \
sed -E 's/^[ \t-]+//' | \
sort -u

echo "-----------------------------------"
echo "Resources Types:"

grep -h -E " Type: AWS" "$output_dir"/*.yaml | \
sed -E 's/^[ \t]*Type: //' | \
sort -u