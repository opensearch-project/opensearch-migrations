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
mkdir -p "${TEMP_DIR}/deployment/global-s3-assets"
mkdir -p "${TEMP_DIR}/deployment/regional-s3-assets"
mkdir -p "${TEMP_DIR}/deployment/open-source"

export CODE_BUCKET SOLUTION_NAME CODE_VERSION

cd "${SCRIPT_DIR}"
npm install

echo "Synthesizing CloudFormation templates..."
npx cdk synth --asset-metadata false --path-metadata false --quiet

cp "cdk.out/Migration-Assistant-Infra-Create-VPC.template.json" "${TEMP_DIR}/deployment/global-s3-assets/${SOLUTION_NAME}-create-vpc.template"
cp "cdk.out/Migration-Assistant-Infra-Import-VPC.template.json" "${TEMP_DIR}/deployment/global-s3-assets/${SOLUTION_NAME}-import-vpc.template"

echo "Copying solution-manifest.yaml..."
cp "${SCRIPT_DIR}/solution-manifest.yaml" "${TEMP_DIR}/solution-manifest.yaml"
sed -i "s/version: .*/version: ${CODE_VERSION}/" "${TEMP_DIR}/solution-manifest.yaml"

touch "${TEMP_DIR}/deployment/regional-s3-assets/test.txt"
touch "${TEMP_DIR}/deployment/open-source/test.txt"

cat > "${TEMP_DIR}/CHANGELOG.md" << EOF
# Changelog
## [${CODE_VERSION}] - $(date +%Y-%m-%d)
### Added
- For detailed changes, please refer to the [GitHub releases page](https://github.com/opensearch-project/opensearch-migrations/releases).
EOF


echo "Creating artifact.zip..."
cd "${TEMP_DIR}"
zip -r "${BUILD_DIR}/artifact.zip" .

echo "Packaging complete. Artifacts in: ${BUILD_DIR}/artifact.zip"
