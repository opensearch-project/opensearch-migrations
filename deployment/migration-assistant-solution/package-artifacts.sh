#!/usr/bin/env bash
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

set -e

if [ -z "$1" ] || [ -z "$2" ] || [ -z "$3" ]; then
    echo "Usage: ./package-artifacts.sh <bucket-name> <solution-name> <version>"
    echo "Example: ./package-artifacts.sh solutions migration-assistant-for-amazon-opensearch-service v1.0.0"
    exit 1
fi

CODE_BUCKET=$1
SOLUTION_NAME=$2
CODE_VERSION=$3
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="${SCRIPT_DIR}/deployment"

echo "Packaging CDK artifacts..."
echo "Bucket: ${CODE_BUCKET}"
echo "Solution: ${SOLUTION_NAME}"
echo "Version: ${CODE_VERSION}"

rm -rf "${OUTPUT_DIR}"
mkdir -p "${OUTPUT_DIR}/global-s3-assets"
mkdir -p "${OUTPUT_DIR}/regional-s3-assets"

export CODE_BUCKET SOLUTION_NAME CODE_VERSION

cd "${SCRIPT_DIR}"
npm install

echo "Synthesizing CloudFormation templates..."
npx cdk synth "Migration-Assistant-Infra-Create-VPC" --asset-metadata false --path-metadata false > "${OUTPUT_DIR}/global-s3-assets/${SOLUTION_NAME}-create-vpc.template"
npx cdk synth "Migration-Assistant-Infra-Import-VPC" --asset-metadata false --path-metadata false > "${OUTPUT_DIR}/global-s3-assets/${SOLUTION_NAME}-import-vpc.template"
npx cdk synth "Migration-Assistant-Infra-Create-VPC-v3" --asset-metadata false --path-metadata false > "${OUTPUT_DIR}/global-s3-assets/${SOLUTION_NAME}-create-vpc-v3.template"
npx cdk synth "Migration-Assistant-Infra-Import-VPC-v3" --asset-metadata false --path-metadata false > "${OUTPUT_DIR}/global-s3-assets/${SOLUTION_NAME}-import-vpc-v3.template"

touch "${OUTPUT_DIR}/regional-s3-assets/test.txt"

echo "Packaging complete. Artifacts in: ${OUTPUT_DIR}"
