#!/usr/bin/env bash

set -e

CODE_BUCKET=deployment
SOLUTION_NAME=migration-assistant-for-amazon-opensearch-service
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${SCRIPT_DIR}/../.."
CODE_VERSION=$(cat "${PROJECT_ROOT}/VERSION" | tr -d '[:space:]')

BUILD_DIR="${SCRIPT_DIR}/build"
TEMP_DIR="${BUILD_DIR}/temp"

echo "Packaging CDK artifacts..."
echo "Bucket: ${CODE_BUCKET}"
echo "Solution: ${SOLUTION_NAME}"
echo "Version: ${CODE_VERSION}"

rm -rf "${BUILD_DIR}"
mkdir -p "${TEMP_DIR}/global-s3-assets"
mkdir -p "${TEMP_DIR}/regional-s3-assets"

export CODE_BUCKET SOLUTION_NAME CODE_VERSION

cd "${SCRIPT_DIR}"
npm install

echo "Synthesizing CloudFormation templates..."
npx cdk synth "Migration-Assistant-Infra-Create-VPC" --asset-metadata false --path-metadata false > "${TEMP_DIR}/global-s3-assets/${SOLUTION_NAME}-create-vpc.template"
npx cdk synth "Migration-Assistant-Infra-Import-VPC" --asset-metadata false --path-metadata false > "${TEMP_DIR}/global-s3-assets/${SOLUTION_NAME}-import-vpc.template"

echo "Copying solutions-manifest.yml..."
cp "${SCRIPT_DIR}/solutions-manifest.yml" "${TEMP_DIR}/global-s3-assets/solutions-manifest.yml"
sed -i "s/version: .*/version: ${CODE_VERSION}/" "${TEMP_DIR}/global-s3-assets/solutions-manifest.yml"

# Waiting for v3.0 release
# npx cdk synth "Migration-Assistant-Infra-Create-VPC-v3" --asset-metadata false --path-metadata false > "${TEMP_DIR}/global-s3-assets/${SOLUTION_NAME}-create-vpc-v3.template"
# npx cdk synth "Migration-Assistant-Infra-Import-VPC-v3" --asset-metadata false --path-metadata false > "${TEMP_DIR}/global-s3-assets/${SOLUTION_NAME}-import-vpc-v3.template"

touch "${TEMP_DIR}/regional-s3-assets/test.txt"

echo "Creating artifact.zip..."
cd "${TEMP_DIR}"
zip -r "${BUILD_DIR}/artifact.zip" .

echo "Packaging complete. Artifacts in: ${BUILD_DIR}/artifact.zip"
