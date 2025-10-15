#!/usr/bin/env bash
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

# Check to see if input has been provided:
if [ -z "$1" ] || [ -z "$2" ] || [ -z "$3" ]; then
    echo "Please provide the base source bucket name, trademark approved solution name and version where the lambda code will eventually reside."
    echo "For example: ./build-s3-dist.sh solutions trademarked-solution-name v1.0.0"
    exit 1
fi

set -e

# Get reference for all important folders
template_dir="$PWD"
template_dist_dir="${template_dir}/global-s3-assets"
build_dist_dir="${template_dir}/regional-s3-assets"
source_dir="${template_dir}/../source"

echo "------------------------------------------------------------------------------"
echo "Rebuild distribution"
echo "------------------------------------------------------------------------------"
rm -rf "${template_dist_dir}"
mkdir -p "${template_dist_dir}"
rm -rf "${build_dist_dir}"
mkdir -p "${build_dist_dir}"

[ -e "${template_dist_dir}" ] && rm -r "${template_dist_dir}"
[ -e "${build_dist_dir}" ] && rm -r "${build_dist_dir}"
mkdir -p "${template_dist_dir}" "${build_dist_dir}"
touch "$build_dist_dir"/test.txt

echo "--------------------------------------------------------------------------------------"
echo "CloudFormation Template generation"
echo "--------------------------------------------------------------------------------------"
export CODE_BUCKET=$1
export SOLUTION_NAME=$2
export CODE_VERSION=$3

cd "$source_dir"/opensearch-migrations/deployment/migration-assistant-solution
npm install
node_modules/aws-cdk/bin/cdk synth "Migration-Assistant-Infra-Create-VPC" --asset-metadata false --path-metadata false > "$template_dist_dir"/"$SOLUTION_NAME"-create-vpc.template
node_modules/aws-cdk/bin/cdk synth "Migration-Assistant-Infra-Import-VPC" --asset-metadata false --path-metadata false > "$template_dist_dir"/"$SOLUTION_NAME"-import-vpc.template

if [ $? -eq 0 ]
then
  echo "Build for $SOLUTION_NAME succeeded"
else
  echo "******************************************************************************"
  echo "Build FAILED for $SOLUTION_NAME"
  echo "******************************************************************************"
  exit 1
fi
