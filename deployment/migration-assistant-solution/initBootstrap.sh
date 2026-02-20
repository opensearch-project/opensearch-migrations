#!/bin/bash

usage() {
  echo "Usage: $0 [--tag <tag_name>] [--branch <branch_name>]"
  exit 1
}

tag=""
branch=""

# Parse options
while [[ "$#" -gt 0 ]]; do
  case $1 in
    --tag)
      if [ -n "$branch" ]; then
        echo "Error: You cannot specify both --tag and --branch."
        usage
      fi
      tag="$2"
      shift
      ;;
    --branch)
      if [ -n "$tag" ]; then
        echo "Error: You cannot specify both --tag and --branch."
        usage
      fi
      branch="$2"
      shift
      ;;
    *)
      echo "Unknown parameter passed: $1"
      usage
      ;;
  esac
  shift
done

# Check that these Node-22/NPM references are valid
yum update && yum install -y git java-17-amazon-corretto-devel docker nodejs22 nodejs22-npm https://s3.amazonaws.com/session-manager-downloads/plugin/latest/linux_64bit/session-manager-plugin.rpm
systemctl start docker
git init
git remote | grep "origin" || git remote add -f origin https://github.com/opensearch-project/opensearch-migrations.git

if [ -n "$branch" ]; then
  git checkout $branch
elif [ -n "$tag" ]; then
  git checkout tags/$tag
else
  latest_release_tag=$(curl -s https://api.github.com/repos/opensearch-project/opensearch-migrations/releases/latest | jq -r ".tag_name")
  git checkout tags/$latest_release_tag
fi

cd deployment/cdk/opensearch-service-migration || exit

if [[ -n "$VPC_ID" ]]; then
  sed -i "s|<VPC_ID>|$VPC_ID|g" /opensearch-migrations/deployment/cdk/opensearch-service-migration/cdk.context.json
fi
if [[ -n "$STAGE" ]]; then
  sed -i "s|<STAGE>|$STAGE|g" /opensearch-migrations/deployment/cdk/opensearch-service-migration/cdk.context.json
fi

npm install -g aws-cdk 2>&1
npm install 2>&1
./buildDockerImages.sh